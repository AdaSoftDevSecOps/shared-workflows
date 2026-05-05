/**
 * SQL Executor (Remote SSH Version)
 * รันบน Jenkins Linux -> สั่งงาน Windows ผ่าน SSH
 */
def call(Map config = [:]) {
    def host = config.host
    def user = config.user
    def sshCredId = config.sshCredId
    def dbName = config.dbName
    def scriptBranch = config.scriptBranch ?: 'main'
    def dbCredId = config.dbCredId
    
    echo "🔍 Remote SQL Execution on ${host} (DB: ${dbName})"

    // 1. Checkout Scripts มาที่เครื่อง Jenkins (Linux)
    dir('temp-sql-scripts') {
        git(
            url: 'https://github.com/AdaSoftDevSecOps/AdaScriptCenter.git',
            branch: scriptBranch,
            credentialsId: 'github-token-cred'
        )
        
        // 2. ส่งไฟล์ SQL และรันผ่าน SSH
        sshagent([sshCredId]) {
            withCredentials([usernamePassword(credentialsId: dbCredId, usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS')]) {
                
                // ตรวจสอบไฟล์ SQL และรันทีละไฟล์
                def scripts = findFiles(glob: 'Script-StoreBack-*.sql')
                scripts.each { script ->
                    echo "🚀 Executing: ${script.name}"
                    
                    // ส่งไฟล์ไปที่เครื่อง Windows ก่อนรัน
                    sh "scp -o StrictHostKeyChecking=no ${script.path} ${user}@${host}:C:/Windows/Temp/${script.name}"
                    
                    // แก้ไขตรงนี้: Escape $ เพื่อไม่ให้ Groovy ตีความผิด
                    def psCommand = "sqlcmd -S ${host} -U ${SQL_USER} -P ${SQL_PASS} -d ${dbName} -i C:/Windows/Temp/${script.name} -b; Remove-Item C:/Windows/Temp/${script.name} -Force"
                    sh "ssh -o StrictHostKeyChecking=no ${user}@${host} \"powershell -Command \\\"${psCommand}\\\"\""
                }
            }
        }
    }
}
