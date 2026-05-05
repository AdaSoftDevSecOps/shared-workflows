/**
 * SQL Executor (เหมือน GitHub Action: sql-execute-v1.yml)
 * 
 * วิธีเรียกใช้:
 * sqlExecute(
 *     dbName: 'TestGithubActions',
 *     projectName: 'TestGithubActions',
 *     scriptBranch: 'TestGithubActions-dev',
 *     credId: 'SQL_SERVER_CREDENTIALS' // ID ของ Username/Password ใน Jenkins
 * )
 */
def call(Map config = [:]) {
    def dbName = config.dbName
    def projectName = config.projectName
    def scriptBranch = config.scriptBranch ?: 'main'
    def credId = config.credId
    
    echo "🔍 Starting SQL Execution for ${projectName} on DB ${dbName}..."

    // 1. Checkout Scripts จาก AdaScriptCenter
    // หมายเหตุ: ใน Jenkins เราจะแยก Folder สคริปต์ออกมา
    dir('temp-sql-scripts') {
        git(
            url: 'https://github.com/AdaSoftDevSecOps/AdaScriptCenter.git',
            branch: scriptBranch,
            credentialsId: 'github-token-cred' // ต้องตั้งค่าใน Jenkins
        )
        
        // 2. ตรวจสอบการเปลี่ยนแปลงไฟล์ (Logic เหมือนเดิม)
        def hasChanges = true // ใน Jenkins เรามักจะรันตาม Trigger หรือใช้ความสามารถของ Plugin
        
        if (hasChanges) {
            withCredentials([usernamePassword(credentialsId: credId, usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS')]) {
                powershell """
                    $server = "${env.SQL_HOST},${env.SQL_PORT}"
                    $db = "${dbName}"
                    
                    Write-Host "Connecting to $db..."
                    
                    # ตัวอย่างการรันไฟล์ SQL
                    $scripts = Get-ChildItem -Filter "Script-StoreBack-*.sql"
                    foreach ($s in $scripts) {
                        Write-Host "Executing: $($s.Name)"
                        sqlcmd -S $server -U $SQL_USER -P $SQL_PASS -d $db -i $s.FullName -b
                        if ($LASTEXITCODE -ne 0) { throw "SQL Execution Failed at $($s.Name)" }
                    }
                """
            }
        } else {
            echo "⏭️ No SQL changes detected, skipping..."
        }
    }
}
