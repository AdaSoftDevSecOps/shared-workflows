/**
 * SQL Executor (Remote SSH Version - Script-based)
 * วิธีนี้จะสร้างไฟล์ PowerShell ขึ้นมาส่งไปรัน เพื่อเลี่ยงปัญหาเรื่อง Quotes ซ้อนกัน
 */
def call(Map config = [:]) {
    def host = config.host
    def user = config.user
    def sshCredId = config.sshCredId
    def sqlHost = config.sqlHost ?: host 
    def sqlPort = config.sqlPort ?: "1433"
    def dbName = config.dbName
    def projectName = config.projectName
    def scriptBranch = config.scriptBranch ?: 'main'
    def dbCredId = config.dbCredId
    def gitCredId = config.gitCredId ?: 'github-token-cred'
    def backupEnabled = config.backupEnabled != null ? config.backupEnabled : true
    def sqlBackupPath = config.sqlBackupPath ?: 'C:/X-BACKUP/SQL'
    def stopOnError = config.stopOnError != null ? config.stopOnError : true
    
    echo "🔍 SQL Execution (Script-based) to ${sqlHost}:${sqlPort}"

    dir('temp-sql-scripts') {
        git(url: 'https://github.com/AdaSoftDevSecOps/AdaScriptCenter.git', branch: scriptBranch, credentialsId: gitCredId)
        
        sshagent([sshCredId]) {
            withCredentials([usernamePassword(credentialsId: dbCredId, usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS')]) {
                
                // 1. ค้นหาสคริปต์ที่มีอยู่จริง
                def targetScripts = ["Script-StoreBack-Structure.sql", "Script-StoreBack-Stored.sql", "Script-StoreBack-Data.sql"]
                def foundScripts = []
                targetScripts.each { if (fileExists(it)) { foundScripts << it } }

                if (foundScripts.isEmpty()) {
                    echo "⏭️ No SQL scripts found. Skipping."
                    return
                }

                // 2. สร้างไฟล์ PowerShell Script (.ps1) สำหรับรันงานทั้งหมด
                def timestamp = new Date().format('yyyyMMdd_HHmmss', TimeZone.getTimeZone('Asia/Bangkok'))
                def psContent = """
                    \$ErrorActionPreference = "Stop"
                    Write-Host "--- SQL Execution Task Start ---"
                    
                    # 1. Connection Verify
                    Write-Host "🔌 Verifying Connection..."
                    sqlcmd -S ${sqlHost},${sqlPort} -U "${SQL_USER}" -P '${SQL_PASS.replace("'", "''")}' -d ${dbName} -Q "SELECT 1" -b -W
                    if (\$LASTEXITCODE -ne 0) { throw "Connection Failed" }

                    # 2. Backup Database
                    if ("${backupEnabled}" -eq "true") {
                        Write-Host "💾 Backing up Database [${dbName}]..."
                        \$backupDir = Join-Path "${sqlBackupPath}" "${projectName}"
                        \$backupFile = "${dbName}_${timestamp}.bak"
                        \$backupDest = Join-Path \$backupDir \$backupFile
                        
                        sqlcmd -S ${sqlHost},${sqlPort} -U "${SQL_USER}" -P '${SQL_PASS.replace("'", "''")}' -Q "EXEC master.dbo.xp_create_subdir N'\$backupDir';" -b
                        \$sqlBackup = "BACKUP DATABASE [${dbName}] TO DISK = N'\$backupDest' WITH INIT, COMPRESSION, CHECKSUM"
                        sqlcmd -S ${sqlHost},${sqlPort} -U "${SQL_USER}" -P '${SQL_PASS.replace("'", "''")}' -Q \$sqlBackup -b
                    }

                    # 3. Execute Scripts
                    Write-Host "🚀 Executing Scripts..."
                    \$scripts = @(${foundScripts.collect { "'$it'" }.join(',')})
                    foreach (\$s in \$scripts) {
                        Write-Host "   Running: \$s"
                        \$fullPath = "C:/Windows/Temp/\$s"
                        sqlcmd -S ${sqlHost},${sqlPort} -U "${SQL_USER}" -P '${SQL_PASS.replace("'", "''")}' -d ${dbName} -i \$fullPath -f 65001 -b -r1
                        if (\$LASTEXITCODE -ne 0) { 
                            if ("${stopOnError}" -eq "true") { throw "Execution Failed at \$s" }
                            else { Write-Warning "Failed at \$s but continuing..." }
                        }
                    }
                    Write-Host "--- Task Completed Successfully ---"
                """.stripIndent().trim()

                writeFile file: 'execute_sql.ps1', text: psContent, encoding: 'UTF-8'

                // 3. ส่งไฟล์ .ps1 และไฟล์ .sql ทั้งหมดไปที่ Windows
                echo "🚚 Transferring files to Windows..."
                sh "scp -o StrictHostKeyChecking=no execute_sql.ps1 ${user}@${host}:C:/Windows/Temp/execute_sql.ps1"
                foundScripts.each { sh "scp -o StrictHostKeyChecking=no ${it} ${user}@${host}:C:/Windows/Temp/${it}" }

                // 4. รันไฟล์ .ps1 ผ่าน SSH
                echo "⚡ Executing Task on Remote Server..."
                def status = sh(
                    script: "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -ExecutionPolicy Bypass -File C:/Windows/Temp/execute_sql.ps1\"",
                    returnStatus: true
                )

                // 5. Cleanup
                sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"Remove-Item C:/Windows/Temp/Script-StoreBack-*.sql, C:/Windows/Temp/execute_sql.ps1 -Force -ErrorAction SilentlyContinue\\\"\""
                
                if (status != 0) {
                    error "❌ SQL Execution Task failed. Check logs above."
                }
                echo "✅ SQL Execution Task Completed!"
            }
        }
    }
}
