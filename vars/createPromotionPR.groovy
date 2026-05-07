/**
 * vars/createPromotionPR.groovy
 * เวอร์ชันปรับปรุง: เพิ่ม Error Handling และ Debug Logging
 */
def call(Map params = [:]) {
    def projectName = params.get('projectName', 'Project')
    def branchMapping = params.get('branchMapping', [:])
    def githubCredId = params.get('githubCredId', 'AdaDevSecOps')
    
    try {
        // 1. ตรวจสอบ Environment ปัจจุบัน
        def currentEnv = getEnvironmentByBranch(branchMapping)
        def currentBranch = env.BRANCH_NAME
        def targetBranch = ""

        def promotionFlow = ['DEV': 'SIT', 'SIT': 'UAT', 'UAT': 'PROD']
        def nextEnv = promotionFlow[currentEnv]
        
        if (!nextEnv) {
            echo "🏁 [PR Manager] สิ้นสุดที่ ${currentEnv}. ไม่มีการส่งงานต่อ"
            return
        }

        targetBranch = branchMapping[nextEnv] ? branchMapping[nextEnv][0] : ""
        if (!targetBranch) {
            echo "⚠️ [PR Manager] ไม่พบกิ่งปลายทางสำหรับ ${nextEnv}"
            return
        }

        echo "🎯 [PR Manager] เป้าหมาย: [${currentEnv} -> ${nextEnv}] กิ่ง: ${targetBranch}"

        // 2. ใช้ Credentials และเริ่มการทำงานกับ GitHub
        withCredentials([string(credentialsId: githubCredId, variable: 'G_TOKEN')]) {
            // ตั้งค่า Environment Variable สำหรับ gh cli
            env.GH_TOKEN = G_TOKEN
            env.GITHUB_TOKEN = G_TOKEN

            echo "🔍 [PR Manager] ตรวจสอบความพร้อมของระบบ..."
            
            // เช็กว่ามี gh cli ไหม
            def hasGH = sh(script: "gh --version", returnStatus: true) == 0
            if (!hasGH) {
                echo "❌ [PR Manager] ไม่พบคำสั่ง 'gh' ในเครื่อง Jenkins Server. กรุณาติดตั้ง GitHub CLI."
                return
            }

            // 3. เตรียมข้อมูล Git (ดึงข้อมูลกิ่งปลายทางมาเทียบ)
            echo "📡 [PR Manager] กำลังดึงข้อมูลจากกิ่ง ${targetBranch}..."
            sh "git fetch origin ${targetBranch} --depth=100"
            
            def commitCountStr = sh(script: "git log origin/${targetBranch}..HEAD --oneline --no-merges | wc -l", returnStdout: true).trim()
            int commitCount = commitCountStr.toInteger()
            
            if (commitCount == 0) {
                echo "✅ [PR Manager] กิ่ง ${currentBranch} เท่ากับ ${targetBranch} แล้ว. ไม่มีงานใหม่."
                return
            }

            echo "📊 [PR Manager] พบ ${commitCount} commits ใหม่"

            def commitList = sh(script: "git log origin/${targetBranch}..HEAD --oneline --no-merges", returnStdout: true).trim()
            def latestSha = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
            def firstAuthor = sh(script: "git log origin/${targetBranch}..HEAD --no-merges --reverse -1 --pretty=%an", returnStdout: true).trim()
            
            // 4. สร้าง PR Body
            def prBody = """
## 🚀 Promotion Request: ${projectName}
| รายละเอียด | ข้อมูล |
|-----------|--------|
| **โปรเจกต์** | ${projectName} |
| **ต้นทาง** | `${currentBranch}` (${currentEnv}) |
| **ปลายทาง** | `${targetBranch}` (${nextEnv}) |
| **จำนวน Commits** | ${commitCount} |
| **ผู้พัฒนาเบื้องต้น** | ${firstAuthor} |

### 📝 รายการ Commit:
```text
${commitList}
```
*🤖 สร้างโดย Jenkins Shared Library*
""".trim()

            def prTitle = "[${projectName}] Promotion: ${currentEnv} -> ${nextEnv} (${commitCount} commits)"

            // 5. ตรวจสอบและจัดการ PR
            echo "📡 [PR Manager] ตรวจสอบสถานะ PR บน GitHub..."
            def existingPR = sh(script: "gh pr list --base ${targetBranch} --head ${currentBranch} --state open --json number --jq '.[0].number'", returnStdout: true).trim()

            if (existingPR && existingPR != "null" && existingPR != "") {
                echo "🔄 [PR Manager] อัปเดต PR #${existingPR}..."
                sh "gh pr edit ${existingPR} --title \"${prTitle}\" --body \"${prBody}\""
                sh "gh pr comment ${existingPR} --body \"📌 **Jenkins Update**: พบโค้ดใหม่ (${commitCount} รายการ)\""
                echo "✅ [PR Manager] อัปเดตสำเร็จ"
            } else {
                echo "✨ [PR Manager] สร้าง PR ใหม่..."
                def prUrl = sh(script: "gh pr create --base \"${targetBranch}\" --head \"${currentBranch}\" --title \"${prTitle}\" --body \"${prBody}\" --label \"auto-promotion\"", returnStdout: true).trim()
                echo "✅ [PR Manager] สร้างสำเร็จ: ${prUrl}"
            }
        }
    } catch (Exception e) {
        // สำคัญ: เราไม่ปล่อยให้ Error ตรงนี้ไปขัดจังหวะ Build หลัก
        echo "❌ [PR Manager] เกิดข้อผิดพลาดในการจัดการ PR: ${e.message}"
        echo "⚠️ การสร้าง PR ไม่สำเร็จ แต่ระบบจะดำเนินการต่อไป..."
    }
}
