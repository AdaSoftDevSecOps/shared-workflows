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
    
    def sourceSubfolder = config.sourceSubfolder ?: "."
    def backupKeepCount = config.backupKeepCount ?: 5
    
    def preserveFiles = config.preserveFiles ?: "config_deploy.php"
    def preserveFolders = config.preserveFolders ?: "Logs,uploads"
    
    def excludeFiles = config.excludeFiles ?: "config_deploy.php"
    def excludeFolders = config.excludeFolders ?: "Logs,uploads,.git,.github"
    
    def date = new Date().format('yyyy-MM-dd_HHmm', TimeZone.getTimeZone('Asia/Bangkok'))
    def timestamp = "${env.BUILD_NUMBER}_${date}"

    echo "🚀 Starting Deployment for ${projectName} to ${host}"

    sshagent([sshCredId]) {
        // --- Step 1: Packaging ---
        echo "📦 Packaging project contents..."
        def excludeCmd = ""
        // ปรับการ Exclude ให้รองรับทั้งไฟล์และโฟลเดอร์
        excludeFolders.split(',').each { if(it) excludeCmd += " '${it}/*'" }
        excludeFiles.split(',').each { if(it) excludeCmd += " '${it}'" }
        
        // ใช้ zip . -x เพื่อเอาเฉพาะเนื้อหาในโฟลเดอร์ปัจจุบัน
        sh "zip -r ${env.WORKSPACE}/project.zip . -x ${excludeCmd}"

        // --- Step 2: Prepare Folder ---
        echo "📁 Preparing directories on Windows..."
        def prepCmd = "if(!(Test-Path '${deployPath}')){ New-Item -ItemType Directory -Path '${deployPath}' -Force }; if(!(Test-Path '${backupPath}')){ New-Item -ItemType Directory -Path '${backupPath}' -Force }; if(!(Test-Path '${configBackupPath}')){ New-Item -ItemType Directory -Path '${configBackupPath}' -Force };"
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"${prepCmd}\\\"\""

        // --- Step 3: Backup & Preserve ---
        echo "💾 Preserving files & backing up current version..."
        def tempBackupDir = "C:\\Windows\\Temp\\deploy_backup_${timestamp}"
        def backupFile = "${backupPath}\\${projectName}_${timestamp}.zip"
        
        def backupAndPreserveCmd = """
            \$ProgressPreference = 'SilentlyContinue';
            if(!(Test-Path '${tempBackupDir}')){ New-Item -ItemType Directory -Path '${tempBackupDir}' -Force };
            
            # สำรองไฟล์ที่ต้องการเก็บไว้ (Preserve Files)
            \$files = '${preserveFiles}'.Split(',');
            foreach(\$f in \$files) {
                if(\$f -and (Test-Path \"${deployPath}\\\$f\")){ 
                    Copy-Item \"${deployPath}\\\$f\" \"${tempBackupDir}\\\$f\" -Force;
                    Copy-Item \"${deployPath}\\\$f\" \"${configBackupPath}\\\$f\" -Force;
                }
            };
            
            # สำรองโฟลเดอร์ที่ต้องการเก็บไว้ (Preserve Folders)
            \$folders = '${preserveFolders}'.Split(',');
            foreach(\$fd in \$folders) {
                if(\$fd -and (Test-Path \"${deployPath}\\\$fd\")){ 
                    Copy-Item \"${deployPath}\\\$fd\" \"${tempBackupDir}\\\$fd\" -Recurse -Force;
                }
            };

            # ทำ Backup เวอร์ชันปัจจุบันทั้งหมดเก็บไว้เป็น ZIP
            if(Test-Path '${deployPath}'){
                \$items = Get-ChildItem '${deployPath}' -Exclude 'project.zip';
                if(\$items.Count -gt 0){
                    Compress-Archive -Path \"${deployPath}\\*\" -DestinationPath '${backupFile}' -Force;
                }
            };
        """.stripIndent().trim().replace('\n', ' ')
        
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"${backupAndPreserveCmd.replace('$', '\\$')}\\\"\""

        // --- Step 4: Deploy ---
        echo "🚚 Transferring and Extracting..."
        // ลบไฟล์เดิมออกก่อน (ยกเว้น project.zip ที่กำลังจะส่งไป)
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"\\\$ProgressPreference = 'SilentlyContinue'; Get-ChildItem '${deployPath}' | Remove-Item -Recurse -Force\\\"\""
        sh "scp -o StrictHostKeyChecking=no ${env.WORKSPACE}/project.zip ${user}@${host}:'${deployPath}\\project.zip'"
        // แตกไฟล์ลงที่ deployPath โดยตรง
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"\\\$ProgressPreference = 'SilentlyContinue'; Expand-Archive -Path '${deployPath}\\project.zip' -DestinationPath '${deployPath}' -Force; Remove-Item '${deployPath}\\project.zip' -Force\\\"\""

        // --- Step 5: Restore ---
        echo "🔄 Restoring preserved files..."
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"\\\$ProgressPreference = 'SilentlyContinue'; if(Test-Path '${tempBackupDir}'){ Copy-Item '${tempBackupDir}\\*' '${deployPath}' -Recurse -Force; Remove-Item '${tempBackupDir}' -Recurse -Force }\\\"\""

        // --- Step 6: Cleanup ---
        echo "🧹 Cleaning up old backups..."
        def cleanupCmd = """
            \$ProgressPreference = 'SilentlyContinue';
            \$backups = Get-ChildItem '${backupPath}' -Filter '*.zip' | Sort-Object LastWriteTime -Descending;
            if(\$backups.Count -gt ${backupKeepCount}){
                \$backups | Select-Object -Skip ${backupKeepCount} | Remove-Item -Force;
            };
        """.stripIndent().trim().replace('\n', ' ')
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"${cleanupCmd.replace('$', '\\$')}\\\"\""
        
        sh "rm -f ${env.WORKSPACE}/project.zip"
        echo "🧹 Cleaning up Agent Workspace..."
        deleteDir()
        echo "✅ Deployment Successful!"
    }
}
