/**
 * SQL Executor (Remote SSH Version - V1 Parity)
 * รันบน Jenkins Linux -> สั่งงาน Windows ผ่าน SSH
 */
def call(Map config = [:]) {
    def host = config.host              // App Server (เป้าหมายที่จะ SSH เข้าไปรันสคริปต์)
    def user = config.user
    def sshCredId = config.sshCredId
    
    // ตั้งค่าของ SQL Server แยกต่างหาก
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
    
    echo "🔍 Remote SQL Execution from ${host} to DB Server: ${sqlHost}:${sqlPort} (DB: ${dbName})"

    // 1. Checkout Scripts มาที่เครื่อง Jenkins (Linux)
    dir('temp-sql-scripts') {
        git(
            url: 'https://github.com/AdaSoftDevSecOps/AdaScriptCenter.git',
            branch: scriptBranch,
            credentialsId: gitCredId
        )
        
        sshagent([sshCredId]) {
            withCredentials([usernamePassword(credentialsId: dbCredId, usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS')]) {
                
                // 2. Validate Scripts
                def targetScripts = [
                    "Script-StoreBack-Structure.sql",
                    "Script-StoreBack-Stored.sql",
                    "Script-StoreBack-Data.sql"
                ]
                def foundScripts = []
                targetScripts.each { scriptName ->
                    if (fileExists(scriptName)) {
                        foundScripts << scriptName
                        echo "  [FOUND] ${scriptName}"
                    }
                }

                if (foundScripts.isEmpty()) {
                    echo "⏭️ No SQL scripts found to execute. Skipping."
                    return
                }

                // 3. Verify SQL Connection (ใช้ ${} เพื่อดึงค่าโดยตรง และครอบด้วย ' ' เพื่อความปลอดภัย)
                echo "🔌 Verifying SQL Connection..."
                def verifyCmd = "sqlcmd -S ${sqlHost},${sqlPort} -U ${SQL_USER} -P '${SQL_PASS}' -d ${dbName} -Q 'SELECT 1' -W -h -1"
                sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"${verifyCmd}\\\"\""
                
                // 4. Backup Database
                if (backupEnabled) {
                    echo "💾 Backing up Database [${dbName}]..."
                    def timestamp = new Date().format('yyyyMMdd_HHmmss', TimeZone.getTimeZone('Asia/Bangkok'))
                    def psBackupCmd = """
                        \$backupDir = Join-Path '${sqlBackupPath}' '${projectName}';
                        \$backupFile = '${dbName}_${timestamp}.bak';
                        \$backupDest = Join-Path \$backupDir \$backupFile;
                        sqlcmd -S ${sqlHost},${sqlPort} -U ${SQL_USER} -P '${SQL_PASS}' -Q "EXEC master.dbo.xp_create_subdir N'\$backupDir';";
                        \$sql = 'BACKUP DATABASE [${dbName}] TO DISK = N\"'\$backupDest'\" WITH INIT, COMPRESSION, CHECKSUM';
                        sqlcmd -S ${sqlHost},${sqlPort} -U ${SQL_USER} -P '${SQL_PASS}' -Q \$sql -b;
                    """.stripIndent().trim().replace('\n', ' ')
                    
                    sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"${psBackupCmd}\\\"\""
                }

                // 5. Execute Scripts
                echo "🚀 Executing Scripts..."
                foundScripts.each { scriptName ->
                    echo "   Running: ${scriptName}"
                    sh "scp -o StrictHostKeyChecking=no ${scriptName} ${user}@${host}:C:/Windows/Temp/${scriptName}"
                    
                    def psExecCmd = "sqlcmd -S ${sqlHost},${sqlPort} -U ${SQL_USER} -P '${SQL_PASS}' -d ${dbName} -i C:/Windows/Temp/${scriptName} -f 65001 -b -r1"
                    def status = sh(script: "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"${psExecCmd}\\\"\"", returnStatus: true)
                    
                    sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"Remove-Item C:/Windows/Temp/${scriptName} -Force\\\"\""
                    
                    if (status != 0) {
                        echo "❌ ERROR: Failed to execute ${scriptName}"
                        if (stopOnError) { error("SQL Execution stopped due to error in ${scriptName}") }
                    }
                }
                echo "✅ SQL Execution Completed Successfully!"
            }
        }
    }
}
