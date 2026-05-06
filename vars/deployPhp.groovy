/**
 * PHP Deployment Template (Remote SSH Version - V3 Clean)
 * Jenkins (Linux) -> Windows Server via SSH/SCP
 */
def call(Map config = [:]) {
    // 1. Load Environment Defaults if specified
    if (config.envName) {
        def envDefaults = getEnvConfig(config.envName)
        config.host = config.host ?: envDefaults.host
        config.user = config.user ?: envDefaults.user
        config.sshCredId = config.sshCredId ?: envDefaults.sshCredId
    }

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
    def deployMode = (config.deployMode ?: 'full').toLowerCase()
    def changedFilesStr = ""

    echo "🚀 Starting Deployment for ${projectName} to ${host} (Mode: ${deployMode})"

    sshagent([sshCredId]) {
        // --- Step 1: Packaging ---
        if (deployMode == 'fast') {
            echo '🔍 Mode: FAST - Detecting changed files since last successful deployment...'
            try {
                def lastSuccess = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT
                def currentCommit = env.GIT_COMMIT

                if (lastSuccess && currentCommit) {
                    echo "   Comparing: ${lastSuccess} -> ${currentCommit}"
                    changedFilesStr = sh(script: "git diff --name-only ${lastSuccess} ${currentCommit}", returnStdout: true).trim().replace('\n', ' ')
                } else {
                    echo "⚠️ No previous successful commit found (First build?). Falling back to FULL mode."
                    deployMode = 'full'
                }

                if (deployMode == 'fast' && !changedFilesStr) {
                    echo "⚠️ No changes detected between commits. Falling back to FULL mode."
                    deployMode = 'full'
                }
            } catch (Exception e) {
                echo "⚠️ Error detecting git diff: ${e.message}. Falling back to FULL mode."
                deployMode = 'full'
            }
        }

        if (deployMode == 'fast') {
            echo "📦 Packaging only changed files: ${changedFilesStr}"
            sh "zip -r ${env.WORKSPACE}/project.zip ${changedFilesStr}"
        } else {
            echo '📦 Packaging project contents (Full)...'
            def excludeCmd = "-x 'project.zip' -x 'temp-sql-scripts*'"
            excludeItems.split(',').each { 
                if (it) {
                    excludeCmd += " -x '${it}'"
                    excludeCmd += " -x '${it}/*'"
                }
            }
            sh "zip -r ${env.WORKSPACE}/project.zip . ${excludeCmd}"
        }

        // --- Step 2: Prepare Folder ---
        echo '📁 Preparing directories on Windows...'
        def prepCmd = """
            if(!(Test-Path '${deployPath}')){ New-Item -ItemType Directory -Path '${deployPath}' -Force }; 
            if(!(Test-Path '${backupPath}')){ New-Item -ItemType Directory -Path '${backupPath}' -Force }; 
            if(!(Test-Path '${configBackupPath}')){ New-Item -ItemType Directory -Path '${configBackupPath}' -Force };
        """.stripIndent().trim()
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} powershell -EncodedCommand ${prepCmd.getBytes('UTF-16LE').encodeBase64().toString()}"

        // --- Step 3: Backup & Preserve ---
        echo "💾 Preserving items (Mode: ${deployMode})..."
        def tempBackupDir = "C:\\Windows\\Temp\\deploy_backup_${timestamp}"
        def backupFile = "${backupPath}\\${projectName}_${timestamp}.zip"
        def changedFilesCsv = changedFilesStr.replace(' ', ',')
        
        def backupAndPreserveCmd = """
            \$ProgressPreference = 'SilentlyContinue';
            if(!(Test-Path "${tempBackupDir}")){ New-Item -ItemType Directory -Path "${tempBackupDir}" -Force };
            
            # 1. Preserve Items (Config Backup)
            \$pItems = "${preserveItems}".Split(',');
            foreach(\$i in \$pItems) {
                \$i = \$i.Trim().Replace('/', '\\');
                \$src = Join-Path "${deployPath}" \$i;
                if(\$i -and (Test-Path \$src)){ 
                    # Persistent Backup
                    if(Test-Path "${configBackupPath}" -PathType Container){
                        \$cfDest = Join-Path "${configBackupPath}" \$i;
                        if(Test-Path \$cfDest){ Remove-Item \$cfDest -Recurse -Force };
                        if(!(Test-Path (Split-Path \$cfDest))){ New-Item -ItemType Directory -Path (Split-Path \$cfDest) -Force };
                        Copy-Item \$src \$cfDest -Recurse -Force;
                    }
                    # Temp Backup for Full Restore
                    if("${deployMode}" -eq "full") {
                        \$dest = Join-Path "${tempBackupDir}" \$i;
                        if(!(Test-Path (Split-Path \$dest))){ New-Item -ItemType Directory -Path (Split-Path \$dest) -Force };
                        Copy-Item \$src \$dest -Recurse -Force;
                    }
                }
            };

            # 2. Deployment Backup
            if("${deployMode}" -eq "fast") {
                \$changedFiles = "${changedFilesCsv}".Split(',');
                \$backedCount = 0;
                foreach(\$f in \$changedFiles) {
                    \$f = \$f.Trim().Replace('/', '\\');
                    \$src = Join-Path "${deployPath}" \$f;
                    if(\$f -and (Test-Path \$src -PathType Leaf)) {
                        \$dest = Join-Path "${tempBackupDir}" \$f;
                        if(!(Test-Path (Split-Path \$dest))){ New-Item -ItemType Directory -Path (Split-Path \$dest) -Force };
                        Copy-Item \$src \$dest -Force;
                        \$backedCount++;
                    }
                }
                if(\$backedCount -gt 0) {
                    Compress-Archive -Path "${tempBackupDir}\\*" -DestinationPath "${backupFile}" -Force;
                }
            } else {
                if(Test-Path "${deployPath}"){
                    \$items = Get-ChildItem "${deployPath}" -Exclude "project.zip";
                    if(\$items.Count -gt 0){
                        Compress-Archive -Path "${deployPath}\\*" -DestinationPath "${backupFile}" -Force;
                    }
                };
            }
        """.stripIndent().trim()
        
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} powershell -EncodedCommand ${backupAndPreserveCmd.getBytes('UTF-16LE').encodeBase64().toString()}"

        // --- Step 4: Deploy & Step 5: Restore (Wrapped in Try-Catch for Rollback) ---
        try {
            echo '🚚 Transferring and Extracting...'
            if (deployMode == 'full') {
                echo '🧹 Mode: FULL - Cleaning destination directory...'
                def cleanCmd = "Get-ChildItem '${deployPath}' | Remove-Item -Recurse -Force"
                sh "ssh -o StrictHostKeyChecking=no ${user}@${host} powershell -EncodedCommand ${cleanCmd.getBytes('UTF-16LE').encodeBase64().toString()}"
            }
            sh "scp -o StrictHostKeyChecking=no ${env.WORKSPACE}/project.zip ${user}@${host}:'${deployPath}\\project.zip'"
            def extractCmd = "Expand-Archive -Path '${deployPath}\\project.zip' -DestinationPath '${deployPath}' -Force; Remove-Item '${deployPath}\\project.zip' -Force"
            sh "ssh -o StrictHostKeyChecking=no ${user}@${host} powershell -EncodedCommand ${extractCmd.getBytes('UTF-16LE').encodeBase64().toString()}"

            if (deployMode == 'full') {
                echo '🔄 Restoring preserved files...'
                def restoreCmd = "if(Test-Path '${tempBackupDir}'){ Copy-Item '${tempBackupDir}\\*' '${deployPath}' -Recurse -Force; Remove-Item '${tempBackupDir}' -Recurse -Force }"
                sh "ssh -o StrictHostKeyChecking=no ${user}@${host} powershell -EncodedCommand ${restoreCmd.getBytes('UTF-16LE').encodeBase64().toString()}"
            } else {
                def cleanupTempCmd = "if(Test-Path '${tempBackupDir}'){ Remove-Item '${tempBackupDir}' -Recurse -Force }"
                sh "ssh -o StrictHostKeyChecking=no ${user}@${host} powershell -EncodedCommand ${cleanupTempCmd.getBytes('UTF-16LE').encodeBase64().toString()}"
            }
        } catch (Exception e) {
            echo "❌ ERROR detected during deployment: ${e.message}"
            echo "🛡️ Initiating Automated Rollback..."
            
            def rollbackCmd = """
                \$ProgressPreference = 'SilentlyContinue';
                if(Test-Path "${backupFile}") {
                    echo "🔄 Restoring from backup: ${backupFile}";
                    # 1. Clean current broken state
                    Get-ChildItem "${deployPath}" | Remove-Item -Recurse -Force;
                    # 2. Extract backup
                    Expand-Archive -Path "${backupFile}" -DestinationPath "${deployPath}" -Force;
                    echo "✅ Rollback completed successfully.";
                } else {
                    echo "⚠️ Rollback failed: Backup file not found.";
                }
                if(Test-Path "${tempBackupDir}"){ Remove-Item "${tempBackupDir}" -Recurse -Force };
            """.stripIndent().trim()
            
            sh "ssh -o StrictHostKeyChecking=no ${user}@${host} powershell -EncodedCommand ${rollbackCmd.getBytes('UTF-16LE').encodeBase64().toString()}"
            error("Deployment failed and was rolled back: ${e.message}")
        }

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