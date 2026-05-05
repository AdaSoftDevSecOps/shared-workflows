/**
 * PHP Deployment Template (Remote SSH Version - Improved Backup)
 * Jenkins (Linux) -> Windows Server via SSH/SCP
 */
def call(Map config = [:]) {
    def host = config.host
    def user = config.user
    def sshCredId = config.sshCredId
    def projectName = config.projectName
    def deployPath = config.deployPath.replace('/', '\\') // ปรับ Path เป็น Windows Style
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
        // --- Step 1: เตรียมไฟล์บนเครื่อง Jenkins (Linux) ---
        echo "📦 Packaging project..."
        def excludeCmd = ""
        excludeFolders.split(',').each { if(it) excludeCmd += " -x '${it}/*'" }
        excludeFiles.split(',').each { if(it) excludeCmd += " -x '${it}'" }
        
        sh "cd ${sourceSubfolder} && zip -r ${env.WORKSPACE}/project.zip ./* ${excludeCmd}"

        // --- Step 2: เตรียม Folder บน Windows ---
        echo "📁 Preparing directories on Windows..."
        def prepCmd = """
            if(!(Test-Path '${deployPath}')){ New-Item -ItemType Directory -Path '${deployPath}' -Force };
            if(!(Test-Path '${backupPath}')){ New-Item -ItemType Directory -Path '${backupPath}' -Force };
            if(!(Test-Path '${configBackupPath}')){ New-Item -ItemType Directory -Path '${configBackupPath}' -Force };
        """.stripIndent().trim().replace('\n', ' ')
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"${prepCmd}\\\"\""

        // --- Step 3: เก็บไฟล์เดิม (Preserve) & Backup ของเก่า ---
        echo "💾 Preserving files & backing up current version..."
        
        def tempBackupDir = "C:\\Windows\\Temp\\deploy_backup_${timestamp}"
        def backupFile = "${backupPath}\\${projectName}_${timestamp}.zip"
        
        def backupAndPreserveCmd = """
            New-Item -ItemType Directory -Path '${tempBackupDir}' -Force;
            
            # 1. เก็บไฟล์สำคัญไปไว้ที่ Temp และ ConfigBackup
            \$files = '${preserveFiles}'.Split(',');
            foreach(\$f in \$files) {
                if(Test-Path '${deployPath}\\\$f'){ 
                    Copy-Item '${deployPath}\\\$f' '${tempBackupDir}\\\$f' -Force;
                    Copy-Item '${deployPath}\\\$f' '${configBackupPath}\\\$f' -Force;
                    Write-Host "Preserved file: \$f";
                }
            }

            # 2. เก็บโฟลเดอร์สำคัญไปไว้ที่ Temp
            \$folders = '${preserveFolders}'.Split(',');
            foreach(\$fd in \$folders) {
                if(Test-Path '${deployPath}\\\$fd'){ 
                    Copy-Item '${deployPath}\\\$fd' '${tempBackupDir}\\\$fd' -Recurse -Force;
                    Write-Host "Preserved folder: \$fd";
                }
            }

            # 3. Backup ของเก่าทั้งก้อน (ถ้ามีไฟล์อยู่)
            if((Get-ChildItem '${deployPath}').Count -gt 0){
                Write-Host "Zipping current version...";
                Compress-Archive -Path '${deployPath}\\*' -DestinationPath '${backupFile}' -Force;
            } else {
                Write-Host "Nothing to backup, folder is empty.";
            }
        """.stripIndent().trim().replace('\n', ' ')
        
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"${backupAndPreserveCmd}\\\"\""

        // --- Step 4: ล้างของเก่าและลงของใหม่ ---
        echo "🚚 Transferring and Extracting..."
        
        // ลบไฟล์ใน deployPath (เว้นไว้แต่โฟลเดอร์ที่ระบบจองไว้)
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"Get-ChildItem '${deployPath}' | Remove-Item -Recurse -Force\\\"\""
        
        // ส่งไฟล์และแตกไฟล์
        sh "scp -o StrictHostKeyChecking=no ${env.WORKSPACE}/project.zip ${user}@${host}:'${deployPath}\\project.zip'"
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"Expand-Archive -Path '${deployPath}\\project.zip' -DestinationPath '${deployPath}' -Force; Remove-Item '${deployPath}\\project.zip' -Force\\\"\""

        // --- Step 5: คืนค่าไฟล์ที่ Preserve ไว้ ---
        echo "🔄 Restoring preserved files..."
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"Copy-Item '${tempBackupDir}\\*' '${deployPath}' -Recurse -Force; Remove-Item '${tempBackupDir}' -Recurse -Force\\\"\""

        // --- Step 6: ลบ Backup เก่า (Keep Count) ---
        echo "🧹 Cleaning up old backups..."
        def cleanupCmd = """
            \$backups = Get-ChildItem '${backupPath}' -Filter '*.zip' | Sort-Object LastWriteTime -Descending;
            if(\$backups.Count -gt ${backupKeepCount}){
                \$backups | Select-Object -Skip ${backupKeepCount} | Remove-Item -Force;
            }
        """.stripIndent().trim().replace('\n', ' ')
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"${cleanupCmd}\\\"\""
        
        // Cleanup เครื่อง Jenkins
        sh "rm -f ${env.WORKSPACE}/project.zip"
        
        echo "✅ Deployment Successful!"
    }
}
