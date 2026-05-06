/**
 * Resolver สำหรับแปลงชื่อ Branch เป็นชื่อ Environment (DEV, SIT, UAT, PROD)
 * รองรับการทำ Mapping แบบยืดหยุ่นสำหรับโปรเจกต์ที่มีโครงสร้าง Branch ต่างกัน
 * 
 * Usage in Jenkinsfile:
 * def targetEnv = getEnvironmentByBranch([
 *     'PROD': ['main', 'master', 'production'],
 *     'SIT' : ['sit', 'release/sit'],
 *     'DEV' : ['develop', 'dev']
 * ])
 */
def call(Map mapping = [:]) {
    def currentBranch = env.BRANCH_NAME ?: 'develop'
    
    // 1. กำหนด Default Mapping (ถ้าโปรเจกต์ไม่ส่งมา)
    def defaultMapping = [
        'PROD': ['main', 'master'],
        'UAT' : ['uat', 'release/uat'],
        'SIT' : ['sit', 'release/sit'],
        'DEV' : ['develop', 'dev']
    ]
    
    // รวม Mapping จากที่ส่งมาทับ Default
    def finalMapping = defaultMapping + mapping

    // 2. ค้นหา Environment จาก Branch ปัจจุบัน
    for (entry in finalMapping) {
        def envName = entry.key
        def branches = entry.value
        
        if (branches.contains(currentBranch)) {
            return envName
        }
    }

    // 3. ถ้าไม่เจอจริงๆ ให้มองว่าเป็น DEV ไว้ก่อนเพื่อความปลอดภัย
    echo "⚠️ Warning: Branch '${currentBranch}' not found in mapping. Defaulting to 'DEV'."
    return 'DEV'
}
