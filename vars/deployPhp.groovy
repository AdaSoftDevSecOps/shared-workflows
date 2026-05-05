/**
 * PHP Deployment Template (Remote SSH Version - V1 Parity)
 * Jenkins (Linux) -> Windows Server via SSH/SCP
 */
def call(Map config = [:]) {
    def host = config.host
    def user = config.user
    def sshCredId = config.sshCredId
    def projectName = config.projectName
    def deployPath = config.deployPath
    def backupPath = config.backupPath
    def configBackupPath = "${backupPath}/ConfigFile"
    
    // ใหม่: รองรับ Source Subfolder และ Backup Keep Count
    def sourceSubfolder = config.sourceSubfolder ?: "."
    def backupKeepCount = config.backupKeepCount ?: 5
    
    // รายชื่อไฟล์/โฟลเดอร์ที่จะเก็บไว้ (Preserve)
    def preserveFiles = config.preserveFiles ?: "config_deploy.php"
    def preserveFolders = config.preserveFolders ?: "Logs,uploads"
    
    // รายชื่อไฟล์/โฟลเดอร์ที่ไม่ต้องส่งไป (Exclude)
    def excludeFiles = config.excludeFiles ?: "config_deploy.php"
    def excludeFolders = config.excludeFolders ?: "Logs,uploads,.git,.github"
    
    def date = new Date().format('yyyy-MM-dd_HHmm', TimeZone.getTimeZone('Asia/Bangkok'))
    def timestamp = "${env.BUILD_NUMBER}_${date}"

    echo "🚀 Starting Deployment for ${projectName} to ${host}"

    sshagent([sshCredId]) {
        // --- Step 1: เตรียมไฟล์บนเครื่อง Jenkins (Linux) ---
        echo "📦 Packaging project from: ${sourceSubfolder}"
        def excludeCmd = ""
        excludeFolders.split(',').each { excludeCmd += " -x '${it}/*'" }
        excludeFiles.split(',').each { excludeCmd += " -x '${it}'" }
        
        // เข้าไปใน subfolder ก่อนแล้วค่อย zip เพื่อไม่ให้ติดโครงสร้างโฟลเดอร์เข้าไป
        sh "cd ${sourceSubfolder} && zip -r ../project.zip ./* ${excludeCmd}"

        // --- Step 2: เตรียม Folder บน Windows ---
        echo "📁 Preparing directories on Windows..."
        def prepCmd = """
            if (!(Test-Path '${backupPath}')) { New-Item -ItemType Directory -Path '${backupPath}' -Force }
            if (!(Test-Path '${configBackupPath}')) { New-Item -ItemType Directory -Path '${configBackupPath}' -Force }
            if (!(Test-Path '${deployPath}')) { New-Item -ItemType Directory -Path '${deployPath}' -Force }
        """.stripIndent().trim().replace('\n', ';')
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"${prepCmd}\\\"\""

        // --- Step 3: Preserve & Backup เดิม (ถ้ามี) ---
        echo "💾 Preserving files & backing up current version..."
        def preserveCmd = """
            # 3.1 Backup Full Current Version
            \$backupFile = '${backupPath}\\${projectName}_${timestamp}.zip'
            if (Test-Path '${deployPath}\\*') {
                & 'C:\\Program Files\\7-Zip\\7z.exe' a -tzip \$backupFile '${deployPath}\\*' -r
            }
            # 3.2 Preserve Specific Files
            '${preserveFiles}'.Split(',') | ForEach-Object {
                \$f = \$_.Trim(); if (Test-Path '${deployPath}\\\$f') { Copy-Item '${deployPath}\\\$f' '${configBackupPath}\\' -Force }
            }
            # 3.3 Preserve Folders (Zip it)
            '${preserveFolders}'.Split(',') | ForEach-Object {
                \$fd = \$_.Trim(); if (Test-Path '${deployPath}\\\$fd') { 
                    & 'C:\\Program Files\\7-Zip\\7z.exe' a -tzip '${configBackupPath}\\\${\$fd}_${timestamp}.zip' '${deployPath}\\\${\$fd}\\*' -r
                }
            }
        """.stripIndent().trim().replace('\n', ';')
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"${preserveCmd}\\\"\""

        // --- Step 4: ส่งไฟล์และแตกไฟล์ ---
        echo "🚚 Transferring and Extracting..."
        sh "scp -o StrictHostKeyChecking=no project.zip ${user}@${host}:\"${deployPath}/project.zip\""
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"tar -xf '${deployPath}\\project.zip' -C '${deployPath}'; Remove-Item '${deployPath}\\project.zip' -Force\\\"\""

        // --- Step 5: Restore Preserved Items ---
        echo "♻️ Restoring preserved files..."
        def restoreCmd = """
            # 5.1 Restore Files
            '${preserveFiles}'.Split(',') | ForEach-Object {
                \$f = \$_.Trim(); if (Test-Path '${configBackupPath}\\\$f') { Copy-Item '${configBackupPath}\\\$f' '${deployPath}\\' -Force }
            }
            # 5.2 Restore Folders (Extract latest zip)
            '${preserveFolders}'.Split(',') | ForEach-Object {
                \$fd = \$_.Trim(); 
                \$latest = Get-ChildItem '${configBackupPath}\\\${\$fd}_*.zip' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
                if (\$latest) { tar -xf \$latest.FullName -C '${deployPath}\\\$fd' }
            }
        """.stripIndent().trim().replace('\n', ';')
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"${restoreCmd}\\\"\""

        // --- Step 6: Cleanup (ใช้ค่า backupKeepCount) ---
        echo "🧹 Cleaning up old backups (Keep: ${backupKeepCount})..."
        def cleanupCmd = """
            Get-ChildItem '${backupPath}\\${projectName}_*.zip' | Sort-Object LastWriteTime -Descending | Select-Object -Skip ${backupKeepCount} | Remove-Item -Force
            Get-ChildItem '${configBackupPath}\\*.zip' | Sort-Object LastWriteTime -Descending | Select-Object -Skip ${backupKeepCount * 2} | Remove-Item -Force
        """.stripIndent().trim().replace('\n', ';')
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"${cleanupCmd}\\\"\""

        // Cleanup local zip
        sh "rm project.zip"
        echo "✅ Deployment Completed Successfully!"
    }
}
