/**
 * SQL Executor (Direct Linux Version - v18 Optimized)
 * รันคำสั่ง sqlcmd จากเครื่อง Jenkins (Linux) โดยใช้ Full Path และรองรับ v18
 */
def call(Map config = [:]) {
    // 1. Load Environment Defaults if specified
    if (config.envName) {
        def envDefaults = getEnvConfig(config.envName)
        config.sqlHost = config.sqlHost ?: envDefaults.sqlHost
        config.sqlPort = config.sqlPort ?: envDefaults.sqlPort
        config.dbCredId = config.dbCredId ?: envDefaults.dbCredId
        config.gitCredId = config.gitCredId ?: envDefaults.gitCredId
        config.sqlBackupPath = config.sqlBackupPath ?: envDefaults.sqlBackupPath
    }

    def sqlHost = config.sqlHost ?: config.host
    def sqlPort = config.sqlPort ?: "1433"
    def dbName = config.dbName
    def projectName = config.projectName
    def scriptBranch = config.scriptBranch ?: 'main'
    def dbCredId = config.dbCredId
    def gitCredId = config.gitCredId ?: 'github-token-cred'
    def backupEnabled = config.backupEnabled != null ? config.backupEnabled : true
    def sqlBackupPath = config.sqlBackupPath ?: 'C:/X-BACKUP/SQL'
    def backupKeepCount = config.backupKeepCount != null ? config.backupKeepCount.toInteger() : 5
    def useTransaction = config.useTransaction != null ? config.useTransaction : true
    def stopOnError = config.stopOnError != null ? config.stopOnError : true
    
    // กำหนด Path ของ sqlcmd ให้แน่นอน
    def sqlcmd = "/opt/mssql-tools18/bin/sqlcmd"
    
    echo "🔍 SQL Execution (Direct Linux v18) to ${sqlHost}:${sqlPort}"

    dir('temp-sql-scripts') {
        try {
            git(url: 'https://github.com/AdaSoftDevSecOps/AdaScriptCenter.git', branch: scriptBranch, credentialsId: gitCredId)
            
            // --- [ADD NEW] SQL Fast Mode Logic ---
            def lastSqlCommitFile = "${env.WORKSPACE}/.last_sql_commit_${projectName}"
            def currentSqlCommit = sh(script: "git rev-parse HEAD", returnStdout: true).trim()
            def previousSqlCommit = fileExists(lastSqlCommitFile) ? readFile(lastSqlCommitFile).trim() : null

            if (currentSqlCommit == previousSqlCommit) {
                echo "⏭️ SQL Fast Mode: No changes detected in SQL scripts (${currentSqlCommit}). Skipping execution."
                return
            }
            echo "🔍 SQL Changes detected or first run. (New: ${currentSqlCommit}, Prev: ${previousSqlCommit ?: 'None'})"

            def targetScripts = ['Script-StoreBack-Structure.sql', 'Script-StoreBack-Stored.sql', 'Script-StoreBack-Data.sql']
            def foundScripts = []
            targetScripts.each { if (fileExists(it)) { foundScripts << it } }

            if (foundScripts.isEmpty()) {
                echo '⏭️ No SQL scripts found in repository. Skipping.'
                return
            }

            withCredentials([usernamePassword(credentialsId: dbCredId, usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS')]) {
                
                // 3. Verify Connection (เพิ่ม -C)
                echo '🔌 Verifying Connection...'
                sh "${sqlcmd} -S ${sqlHost},${sqlPort} -U \$SQL_USER -P \$SQL_PASS -d ${dbName} -Q 'SELECT 1' -b -W -C"

                // 4. Backup Database
                if (backupEnabled) {
                    echo "💾 Backing up Database [${dbName}]..."
                    def timestamp = new Date().format('yyyyMMdd_HHmmss', TimeZone.getTimeZone('Asia/Bangkok'))
                    
                    // ปรับ Path ให้เป็น Backslash (\) สำหรับ Windows
                    def winBackupPath = sqlBackupPath.replace('/', '\\')
                    def backupDir = "${winBackupPath}\\${projectName}"
                    def backupDest = "${backupDir}\\${dbName}_${timestamp}.bak"
                    
                    echo "   Backup Path: ${backupDest}"

                    // สร้าง Folder (สั่งผ่าน SQL)
                    def createDirSql = "EXEC master.dbo.xp_create_subdir N'${backupDir}';"
                    sh "${sqlcmd} -S ${sqlHost},${sqlPort} -U \$SQL_USER -P \$SQL_PASS -Q \"${createDirSql}\" -b -C"
                    
                    // สั่ง Backup
                    def backupSql = "BACKUP DATABASE [${dbName}] TO DISK = N'${backupDest}' WITH INIT, COMPRESSION, CHECKSUM"
                    sh "${sqlcmd} -S ${sqlHost},${sqlPort} -U \$SQL_USER -P \$SQL_PASS -Q \"${backupSql}\" -b -C"

                    // --- [ADD NEW] Verify Backup ---
                    echo "🔍 Verifying Backup integrity..."
                    def verifySql = """
                        DECLARE @file_exists INT;
                        CREATE TABLE #file_check (exists_bit INT, is_directory INT, parent_directory_exists INT);
                        INSERT INTO #file_check EXEC master.dbo.xp_fileexist N'${backupDest}';
                        SELECT @file_exists = exists_bit FROM #file_check;
                        DROP TABLE #file_check;

                        IF @file_exists = 1
                        BEGIN
                            PRINT '✅ File exists. Verifying integrity...';
                            RESTORE VERIFYONLY FROM DISK = N'${backupDest}';
                            PRINT '✅ Backup is valid.';
                        END
                        ELSE
                        BEGIN
                            RAISERROR('❌ ERROR: Backup file not found after backup operation!', 16, 1);
                        END
                    """.stripIndent().trim()
                    
                    def verifyStatus = sh(
                        script: "${sqlcmd} -S ${sqlHost},${sqlPort} -U \$SQL_USER -P \$SQL_PASS -Q \"${verifySql}\" -b -C",
                        returnStatus: true
                    )

                    if (verifyStatus != 0) {
                        error("❌ Database Backup Verification FAILED for [${dbName}]. Stopping process to prevent data loss.")
                    }

                    // 5. Cleanup Old Backups (ใช้ xp_cmdshell โดยผ่านไฟล์ SQL เพื่อเลี่ยงปัญหา Quoting)
                    if (backupKeepCount > 0) {
                        echo "🧹 Cleaning up old backups in ${backupDir} (Keeping top ${backupKeepCount})..."
                        
                        def cleanupFile = 'cleanup_backup.sql'
                        def cleanupCmd = "powershell.exe -Command \"Get-ChildItem -Path '${backupDir}' -Filter '${dbName}_*.bak' | Sort-Object CreationTime -Descending | Select-Object -Skip ${backupKeepCount} | Remove-Item -Force\""
                        
                        // ต้องเปลี่ยน ' เป็น '' เพื่อให้ SQL string ทำงานได้ถูกต้อง
                        writeFile file: cleanupFile, text: "EXEC xp_cmdshell '${cleanupCmd.replace("'", "''")}';", encoding: 'UTF-8'
                        
                        // รันโดยไม่ใส่ -b เพื่อไม่ให้ Build Fail ถ้าไม่ได้เปิด xp_cmdshell และระบุ code page UTF-8
                        sh "${sqlcmd} -S ${sqlHost},${sqlPort} -U \$SQL_USER -P \$SQL_PASS -i ${cleanupFile} -f 65001 -C"
                    }
                }

                // 6. Execute Scripts
                if (useTransaction) {
                    echo '🚀 Executing all scripts in a single Transaction (All-or-Nothing)...'
                    def masterFile = 'master_deploy.sql'
                    def masterContent = """
                        SET NOCOUNT OFF;
                        SET QUOTED_IDENTIFIER ON;
                        SET ANSI_NULLS ON;
                        SET XACT_ABORT ON;
                        BEGIN TRANSACTION;
                        GO
                    """.stripIndent().trim() + "\n"

                    foundScripts.each { script ->
                        masterContent += "PRINT '--- [RUNNING] ${script} ---';\n"
                        masterContent += "GO\n"
                        masterContent += ":r ${script}\n"
                        masterContent += "GO\n"
                    }
                    masterContent += "COMMIT TRANSACTION;\nPRINT '✅ All scripts committed successfully.';\nGO\n"
                    
                    writeFile file: masterFile, text: masterContent, encoding: 'UTF-8'

                    def status = sh(
                        script: "${sqlcmd} -S ${sqlHost},${sqlPort} -U \$SQL_USER -P \$SQL_PASS -d ${dbName} -i ${masterFile} -f 65001 -b -C -m -1",
                        returnStatus: true
                    )

                    if (status != 0) {
                        echo '❌ ERROR: SQL Execution failed. Changes have been rolled back automatically.'
                        if (stopOnError) { error('SQL Execution failed in Transactional mode.') }
                    } else {
                        echo '✅ SQL Execution Completed Successfully!'
                        // บันทึกความสำเร็จลงไฟล์เพื่อให้ครั้งหน้าข้ามได้
                        writeFile file: lastSqlCommitFile, text: currentSqlCommit
                    }
                } else {
                    echo '🚀 Executing Scripts one by one...'
                    foundScripts.each { script ->
                        echo "--- [RUNNING] ${script} ---"
                        def status = sh(
                            script: "${sqlcmd} -S ${sqlHost},${sqlPort} -U \$SQL_USER -P \$SQL_PASS -d ${dbName} -i ${script} -f 65001 -b -r1 -C",
                            returnStatus: true
                        )
                        
                        if (status != 0) {
                            echo "❌ ERROR: Failed to execute ${script}"
                            if (stopOnError) { error("SQL Execution stopped due to error in ${script}") }
                        } else {
                            echo "✅ Success: ${script}"
                        }
                        echo '-----------------------------------'
                    }
                    echo '✅ SQL Execution Completed Successfully!'
                    // บันทึกความสำเร็จลงไฟล์เพื่อให้ครั้งหน้าข้ามได้
                    writeFile file: lastSqlCommitFile, text: currentSqlCommit
                }
            }
        } finally {
            echo '🧹 Cleaning up SQL temp folder on Agent...'
            deleteDir()
        }
    }
}
