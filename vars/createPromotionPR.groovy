/**
 * vars/createPromotionPR.groovy
 * เวอร์ชันเน้นความเสถียร (Compatibility Mode): ลดความซับซ้อนของคำสั่ง gh เพื่อเลี่ยงปัญหาบั๊กในเวอร์ชันเก่า
 */
def call(Map params = [:]) {
    def projectName = params.get('projectName', 'Project')
    def branchMapping = params.get('branchMapping', [:])
    def githubCredId = params.get('githubCredId', 'AdaDevSecOps')
    
    try {
        def currentEnv = getEnvironmentByBranch(branchMapping)
        def currentBranch = env.BRANCH_NAME
        def targetBranch = ""

        def promotionFlow = ['DEV': 'SIT', 'SIT': 'UAT', 'UAT': 'PROD']
        def nextEnv = promotionFlow[currentEnv]
        
        if (!nextEnv) {
            echo "🏁 [PR Manager] สิ้นสุดที่ ${currentEnv}."
            return
        }

        targetBranch = branchMapping[nextEnv] ? branchMapping[nextEnv][0] : ""
        if (!targetBranch) {
            echo "⚠️ [PR Manager] ไม่พบกิ่งปลายทางสำหรับ ${nextEnv}"
            return
        }

        echo "🎯 [PR Manager] เป้าหมาย: [${currentEnv} -> ${nextEnv}] กิ่ง: ${targetBranch}"

        withCredentials([usernamePassword(credentialsId: githubCredId, usernameVariable: 'G_USER', passwordVariable: 'G_TOKEN')]) {
            env.GH_TOKEN = G_TOKEN
            env.GITHUB_TOKEN = G_TOKEN

            echo "📡 [PR Manager] กำลังดึงข้อมูลจาก GitHub..."
            checkout scm
            sh "git -c url.\"https://${G_TOKEN}@github.com/\".insteadOf=\"https://github.com/\" fetch origin ${targetBranch} --depth=100"
            
            def commitCountStr = sh(script: "git log FETCH_HEAD..HEAD --oneline --no-merges | wc -l", returnStdout: true).trim()
            int commitCount = commitCountStr.toInteger()
            
            if (commitCount == 0) {
                echo "✅ [PR Manager] กิ่ง ${currentBranch} เท่ากับ ${targetBranch} แล้ว."
                return
            }

            def commitList = sh(script: "git log FETCH_HEAD..HEAD --oneline --no-merges", returnStdout: true).trim()
            def firstAuthor = sh(script: "git log FETCH_HEAD..HEAD --no-merges --reverse -1 --pretty=%an", returnStdout: true).trim()
            
            // เตรียมเนื้อหา PR
            def prBody = """
## 🚀 Promotion Request: ${projectName}
| รายละเอียด | ข้อมูล |
|-----------|--------|
| **โปรเจกต์** | ${projectName} |
| **ต้นทาง** | `${currentBranch}` (${currentEnv}) |
| **ปลายทาง** | `${targetBranch}` (${nextEnv}) |
| **จำนวน Commits** | ${commitCount} |
| **ผู้พัฒนา** | ${firstAuthor} |

### 📝 รายการ Commit ใหม่:
```text
${commitList}
```
*🤖 อัปเดตอัตโนมัติโดย Jenkins*
""".trim()

            writeFile file: 'pr_body.txt', text: prBody
            
            // กำหนด Title แบบเรียบง่ายที่สุด
            env.PR_TITLE = "[${projectName}] Promotion: ${currentEnv} -> ${nextEnv}"
            env.PR_COMMENT = "📌 **Update**: พบโค้ดใหม่ ${commitCount} รายการ พร้อมให้ Review ครับ"

            echo "🔍 [PR Manager] ตรวจสอบ PR เดิม..."
            def existingPR = sh(script: "gh pr list --base ${targetBranch} --head ${currentBranch} --state open --json number --jq '.[0].number'", returnStdout: true).trim()

            if (existingPR && existingPR != "null" && existingPR != "") {
                echo "🔄 [PR Manager] พบ PR #${existingPR} - กำลังส่งคอมเมนต์อัปเดต..."
                // ใช้การ Comment แทนการ Edit เพื่อลดความเสี่ยง Error
                sh "gh pr comment \"${existingPR}\" --body \"\$PR_COMMENT\""
                echo "✅ [PR Manager] ส่งคอมเมนต์อัปเดตเรียบร้อย"
            } else {
                echo "✨ [PR Manager] ไม่พบ PR เดิม - กำลังสร้างใหม่..."
                sh "gh pr create --base \"${targetBranch}\" --head \"${currentBranch}\" --title \"\$PR_TITLE\" --body-file pr_body.txt"
                echo "✅ [PR Manager] สร้าง PR ใหม่สำเร็จ"
            }
            
            sh "rm -f pr_body.txt"
        }
    } catch (Exception e) {
        echo "❌ [PR Manager] Error: ${e.message}"
    }
}
