/**
 * PHP Deployment Template (Remote SSH Version - V3 Clean)
 * Jenkins (Linux) -> Windows Server via SSH/SCP
 */
def call(Map config = [:]) {
    def host = config.host
    def user = config.user
    def sshCredId = config.sshCredId
    def projectName = config.projectName
    def deployPath = config.deployPath.replace('/', '\\')
    def backupPath = config.backupPath.replace('/', '\\')
    def configBackupPath = "${backupPath}\\ConfigFile"

    def sourceSubfolder = config.sourceSubfolder ?: '.'
    def backupKeepCount = config.backupKeepCount ?: 5

    // Combined preserve/exclude items (Handles both files and folders)
    def preserveItems = (config.preserveItems ?: "${config.preserveFiles ?: 'config_deploy.php'},${config.preserveFolders ?: 'Logs,uploads'}")
                        .split(',').collect{ it.trim() }.unique().findAll{ it }.join(',')
    
    def excludeItems  = (config.excludeItems ?: "${config.excludeFiles ?: 'config_deploy.php'},${config.excludeFolders ?: 'Logs,uploads,.git,.github'}")
                        .split(',').collect{ it.trim() }.unique().findAll{ it }.join(',')

    def date = new Date().format('yyyy-MM-dd_HHmm', TimeZone.getTimeZone('Asia/Bangkok'))
    def timestamp = "${env.BUILD_NUMBER}_${date}"

    echo "🚀 Starting Deployment for ${projectName} to ${host}"

    sshagent([sshCredId]) {
        // --- Step 1: Packaging ---
        echo '📦 Packaging project contents (Excluding temp files)...'
        def excludeCmd = "-x 'project.zip' -x 'temp-sql-scripts*'"
        excludeItems.split(',').each { 
            if (it) {
                excludeCmd += " -x '${it}'"
                excludeCmd += " -x '${it}/*'"
            }
        }
        sh "zip -r ${env.WORKSPACE}/project.zip . ${excludeCmd}"

        // --- Step 2: Prepare Folder ---
        echo '📁 Preparing directories on Windows...'
        def prepCmd = """
            if(!(Test-Path '${deployPath}')){ New-Item -ItemType Directory -Path '${deployPath}' -Force }; 
            if(!(Test-Path '${backupPath}')){ New-Item -ItemType Directory -Path '${backupPath}' -Force }; 
            if(!(Test-Path '${configBackupPath}')){ New-Item -ItemType Directory -Path '${configBackupPath}' -Force };
        """.stripIndent().trim()
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} powershell -EncodedCommand ${prepCmd.getBytes('UTF-16LE').encodeBase64().toString()}"

        // --- Step 3: Backup & Preserve ---
        echo '💾 Preserving files & backing up current version...'
        def tempBackupDir = "C:\\Windows\\Temp\\deploy_backup_${timestamp}"
        def backupFile = "${backupPath}\\${projectName}_${timestamp}.zip"
        
        def backupAndPreserveCmd = """
            \$ProgressPreference = 'SilentlyContinue';
            if(!(Test-Path "${tempBackupDir}")){ New-Item -ItemType Directory -Path "${tempBackupDir}" -Force };
            
            \$items = "${preserveItems}".Split(',');
            foreach(\$i in \$items) {
                \$i = \$i.Trim().Replace('/', '\\');
                \$src = Join-Path "${deployPath}" \$i;
                if(\$i -and (Test-Path \$src)){ 
                    # 1. Temporary Backup (for restoration after cleaning)
                    \$dest = Join-Path "${tempBackupDir}" \$i;
                    if(!(Test-Path (Split-Path \$dest))){ New-Item -ItemType Directory -Path (Split-Path \$dest) -Force };
                    Copy-Item \$src \$dest -Recurse -Force;

                    # 2. Persistent Backup (ConfigFile folder)
                    if(Test-Path "${configBackupPath}" -PathType Container){
                        \$cfDest = Join-Path "${configBackupPath}" \$i;
                        if(Test-Path \$cfDest){ Remove-Item \$cfDest -Recurse -Force };
                        if(!(Test-Path (Split-Path \$cfDest))){ New-Item -ItemType Directory -Path (Split-Path \$cfDest) -Force };
                        Copy-Item \$src \$cfDest -Recurse -Force;
                    }
                }
            };

            if(Test-Path "${deployPath}"){
                \$items = Get-ChildItem "${deployPath}" -Exclude "project.zip";
                if(\$items.Count -gt 0){
                    Compress-Archive -Path "${deployPath}\\*" -DestinationPath "${backupFile}" -Force;
                }
            };
        """.stripIndent().trim()
        
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} powershell -EncodedCommand ${backupAndPreserveCmd.getBytes('UTF-16LE').encodeBase64().toString()}"

        // --- Step 4: Deploy ---
        echo '🚚 Transferring and Extracting...'
        def cleanCmd = "Get-ChildItem '${deployPath}' | Remove-Item -Recurse -Force"
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} powershell -EncodedCommand ${cleanCmd.getBytes('UTF-16LE').encodeBase64().toString()}"
        sh "scp -o StrictHostKeyChecking=no ${env.WORKSPACE}/project.zip ${user}@${host}:'${deployPath}\\project.zip'"
        def extractCmd = "Expand-Archive -Path '${deployPath}\\project.zip' -DestinationPath '${deployPath}' -Force; Remove-Item '${deployPath}\\project.zip' -Force"
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} powershell -EncodedCommand ${extractCmd.getBytes('UTF-16LE').encodeBase64().toString()}"

        // --- Step 5: Restore ---
        echo '🔄 Restoring preserved files...'
        def restoreCmd = "if(Test-Path '${tempBackupDir}'){ Copy-Item '${tempBackupDir}\\*' '${deployPath}' -Recurse -Force; Remove-Item '${tempBackupDir}' -Recurse -Force }"
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} powershell -EncodedCommand ${restoreCmd.getBytes('UTF-16LE').encodeBase64().toString()}"

        // --- Step 6: Cleanup ---
        echo '🧹 Cleaning up old backups...'
        def cleanupCmd = """
            \$ProgressPreference = 'SilentlyContinue';
            \$backups = Get-ChildItem '${backupPath}' -Filter '*.zip' | Sort-Object LastWriteTime -Descending;
            if(\$backups.Count -gt ${backupKeepCount}){
                \$backups | Select-Object -Skip ${backupKeepCount} | Remove-Item -Force;
            };
        """.stripIndent().trim()
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} powershell -EncodedCommand ${cleanupCmd.getBytes('UTF-16LE').encodeBase64().toString()}"
        
        sh "rm -f ${env.WORKSPACE}/project.zip"
        echo '🧹 Cleaning up Agent Workspace...'
        deleteDir()
        echo '✅ Deployment Successful!'
    }
}