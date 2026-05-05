/**
 * SQL Executor (Direct Linux Version - v18 Optimized)
 * รันคำสั่ง sqlcmd จากเครื่อง Jenkins (Linux) โดยใช้ Full Path และรองรับ v18
 */
def call(Map config = [:]) {
    def sqlHost = config.sqlHost ?: config.host
    def sqlPort = config.sqlPort ?: "1433"
    def dbName = config.dbName
    def projectName = config.projectName
    def scriptBranch = config.scriptBranch ?: 'main'
    def dbCredId = config.dbCredId
    def gitCredId = config.gitCredId ?: 'github-token-cred'
    def backupEnabled = config.backupEnabled != null ? config.backupEnabled : true
    def sqlBackupPath = config.sqlBackupPath ?: 'C:/X-BACKUP/SQL'
    def stopOnError = config.stopOnError != null ? config.stopOnError : true
    
    // กำหนด Path ของ sqlcmd ให้แน่นอน
    def sqlcmd = "/opt/mssql-tools18/bin/sqlcmd"
    
    echo "🔍 SQL Execution (Direct Linux v18) to ${sqlHost}:${sqlPort}"

    dir('temp-sql-scripts') {
        git(url: 'https://github.com/AdaSoftDevSecOps/AdaScriptCenter.git', branch: scriptBranch, credentialsId: gitCredId)
        
        def targetScripts = ["Script-StoreBack-Structure.sql", "Script-StoreBack-Stored.sql", "Script-StoreBack-Data.sql"]
        def foundScripts = []
        targetScripts.each { if (fileExists(it)) { foundScripts << it } }

        if (foundScripts.isEmpty()) {
            echo "⏭️ No SQL scripts found. Skipping."
            return
        }

        withCredentials([usernamePassword(credentialsId: dbCredId, usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS')]) {
            
            // 3. Verify Connection (เพิ่ม -C)
            echo "🔌 Verifying Connection..."
            sh "${sqlcmd} -S ${sqlHost},${sqlPort} -U \$SQL_USER -P \$SQL_PASS -d ${dbName} -Q 'SELECT 1' -b -W -C"

            // 4. Backup Database
            if (backupEnabled) {
                echo "💾 Backing up Database [${dbName}]..."
                def timestamp = new Date().format('yyyyMMdd_HHmmss', TimeZone.getTimeZone('Asia/Bangkok'))
                def backupDest = "${sqlBackupPath}/${projectName}/${dbName}_${timestamp}.bak"
                
                def createDirSql = "EXEC master.dbo.xp_create_subdir N'${sqlBackupPath}/${projectName}';"
                sh "${sqlcmd} -S ${sqlHost},${sqlPort} -U \$SQL_USER -P \$SQL_PASS -Q \"${createDirSql}\" -b -C"
                
                def backupSql = "BACKUP DATABASE [${dbName}] TO DISK = N'${backupDest}' WITH INIT, COMPRESSION, CHECKSUM"
                sh "${sqlcmd} -S ${sqlHost},${sqlPort} -U \$SQL_USER -P \$SQL_PASS -Q \"${backupSql}\" -b -C"
            }

            // 5. Execute Scripts
            echo "🚀 Executing Scripts..."
            foundScripts.each { script ->
                echo "   Running: ${script}"
                def status = sh(
                    script: "${sqlcmd} -S ${sqlHost},${sqlPort} -U \$SQL_USER -P \$SQL_PASS -d ${dbName} -i ${script} -f 65001 -b -r1 -C",
                    returnStatus: true
                )
                
                if (status != 0) {
                    echo "❌ ERROR: Failed to execute ${script}"
                    if (stopOnError) { error("SQL Execution stopped due to error in ${script}") }
                }
            }
            echo "✅ SQL Execution Completed Successfully!"
        }
    }
}
