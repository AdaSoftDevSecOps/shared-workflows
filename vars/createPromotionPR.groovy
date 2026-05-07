/**
 * vars/createPromotionPR.groovy
 * เวอร์ชันปรับปรุง: ใช้ Environment Variables เพื่อความปลอดภัยสูงสุดในการส่งค่าไปยัง Shell
 */
def call(Map params = [:]) {
    def projectName = params.get('projectName', 'Project')
    def branchMapping = params.get('branchMapping', [:])
    def githubCredId = params.get('githubCredId', 'AdaDevSecOps')
    
    try {
        // 1. ตรวจสอบ Environment
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

        // 2. ใช้ Credentials
        withCredentials([usernamePassword(credentialsId: githubCredId, usernameVariable: 'G_USER', passwordVariable: 'G_TOKEN')]) {
            env.GH_TOKEN = G_TOKEN
            env.GITHUB_TOKEN = G_TOKEN

            // 3. เตรียมข้อมูล Git
            echo "📡 [PR Manager] กำลังดึงข้อมูลจากกิ่ง ${targetBranch}..."
            
            // ป้องกันปัญหา .git หายไปจาก Workspace
            checkout scm
            
            sh "git -c url.\"https://${G_TOKEN}@github.com/\".insteadOf=\"https://github.com/\" fetch origin ${targetBranch} --depth=100"
            
            def commitCountStr = sh(script: "git log FETCH_HEAD..HEAD --oneline --no-merges | wc -l", returnStdout: true).trim()
            int commitCount = commitCountStr.toInteger()
            
            if (commitCount == 0) {
                echo "✅ [PR Manager] กิ่ง ${currentBranch} เท่ากับ ${targetBranch} แล้ว. ไม่มีงานใหม่."
                return
            }

            echo "📊 [PR Manager] พบ ${commitCount} commits ใหม่"

            def commitList = sh(script: "git log FETCH_HEAD..HEAD --oneline --no-merges", returnStdout: true).trim()
            def latestSha = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
            def firstAuthor = sh(script: "git log FETCH_HEAD..HEAD --no-merges --reverse -1 --pretty=%an", returnStdout: true).trim()
            
            // 4. สร้าง PR Body (เขียนลงไฟล์)
            def prBody = """
## 🚀 Promotion Request: ${projectName}

| รายละเอียด | ข้อมูล |
|-----------|--------|
| **โปรเจกต์** | ${projectName} |
| **ต้นทาง** | `${currentBranch}` (${currentEnv}) |
| **ปลายทาง** | `${targetBranch}` (${nextEnv}) |
| **จำนวน Commits** | ${commitCount} |
| **ผู้พัฒนาเบื้องต้น** | ${firstAuthor} |

---

### 📝 รายการ Commit:
```text
${commitList}
```

---

### ✅ ขั้นตอนถัดไป:
1. 🔍 Review โค้ดใน PR นี้
2. 👍 Approve และ Merge เพื่อ Deploy ไปยัง **${nextEnv}**

*🤖 สร้าง/อัปเดตอัตโนมัติโดย Jenkins Shared Library*
""".trim()

            writeFile file: 'pr_body.txt', text: prBody
            
            // 5. ส่งค่าผ่าน Environment Variables เพื่อหลีกเลี่ยงปัญหา Shell Escaping
            env.PR_TITLE = "[${projectName}] Promotion: ${currentEnv} -> ${nextEnv} (${commitCount} commits)"
            env.PR_COMMENT_BODY = "📌 **Jenkins Update**: พบโค้ดใหม่ (${commitCount} รายการ) พร้อมสำหรับการส่งงานต่อ"

            // 6. ตรวจสอบและจัดการ PR
            echo "📡 [PR Manager] ตรวจสอบสถานะ PR บน GitHub..."
            def existingPR = sh(script: "gh pr list --base ${targetBranch} --head ${currentBranch} --state open --json number --jq '.[0].number'", returnStdout: true).trim()

            if (existingPR && existingPR != "null" && existingPR != "") {
                echo "🔄 [PR Manager] อัปเดต PR #${existingPR}..."
                // ใช้ double-quote ครอบตัวแปร env (ที่เป็น $) เพื่อให้ Shell อ่านค่าตรงๆ
                sh "gh pr edit \"${existingPR}\" --title \"\$PR_TITLE\" --body-file pr_body.txt"
                sh "gh pr comment \"${existingPR}\" --body \"\$PR_COMMENT_BODY\""
                echo "✅ [PR Manager] อัปเดตสำเร็จ"
            } else {
                echo "✨ [PR Manager] สร้าง PR ใหม่..."
                def prUrl = sh(script: "gh pr create --base \"${targetBranch}\" --head \"${currentBranch}\" --title \"\$PR_TITLE\" --body-file pr_body.txt", returnStdout: true).trim()
                echo "✅ [PR Manager] สร้างสำเร็จ: ${prUrl}"
            }
            
            sh "rm -f pr_body.txt"
        }
    } catch (Exception e) {
        echo "❌ [PR Manager] เกิดข้อผิดพลาดในการจัดการ PR: ${e.message}"
        echo "⚠️ การสร้าง PR ไม่สำเร็จ แต่ระบบจะดำเนินการต่อไป..."
    }
}
