/**
 * vars/createPromotionPR.groovy
 * ฟีเจอร์: สร้างและจัดการ PR อัตโนมัติสำหรับการเลื่อนขั้น (Promotion)
 * รองรับการทำ Mapping อย่างยืดหยุ่นตามโครงสร้างโปรเจกต์
 */
def call(Map params = [:]) {
    def projectName = params.get('projectName', 'Project')
    def branchMapping = params.get('branchMapping', [:]) // รับ Mapping จาก Jenkinsfile
    def githubCredId = params.get('githubCredId', 'AdaDevSecOps')
    
    // 1. ตรวจสอบ Environment ปัจจุบันจาก Branch
    def currentEnv = getEnvironmentByBranch(branchMapping)
    def currentBranch = env.BRANCH_NAME
    def targetBranch = ""

    // 2. กำหนดลำดับการ Promotion (Flow)
    def promotionFlow = [
        'DEV': 'SIT',
        'SIT': 'UAT',
        'UAT': 'PROD'
    ]

    def nextEnv = promotionFlow[currentEnv]
    
    if (!nextEnv) {
        echo "🏁 [PR Manager] สิ้นสุดที่ ${currentEnv}. ไม่มีการส่งงานต่ออัตโนมัติ"
        return
    }

    // 3. หาชื่อกิ่งปลายทาง (Target Branch) จาก Mapping
    // ถ้าใน mapping มี SIT: ['sit'], มันจะดึง 'sit' มาใช้
    targetBranch = branchMapping[nextEnv] ? branchMapping[nextEnv][0] : ""

    if (!targetBranch) {
        echo "⚠️ [PR Manager] ไม่พบการตั้งชื่อกิ่งสำหรับ ${nextEnv} ใน branchMapping. ข้าม..."
        return
    }

    echo "🎯 [PR Manager] ตรวจพบแผนงาน: [${currentEnv} -> ${nextEnv}] เป้าหมายกิ่ง: ${targetBranch}"

    // ดึง GitHub Token จาก Jenkins Credentials
    withCredentials([string(credentialsId: githubCredId, variable: 'GITHUB_TOKEN')]) {
        
        // 4. เตรียมข้อมูล Commit (Smart Detection)
        sh "git fetch origin ${targetBranch} --depth=100"
        
        def commitCount = sh(script: "git log origin/${targetBranch}..HEAD --oneline --no-merges | wc -l", returnStdout: true).trim()
        
        if (commitCount == "0" || commitCount == "") {
            echo "✅ [PR Manager] กิ่ง ${currentBranch} อัปเดตล่าสุดแล้ว (ไม่มี Commit ใหม่ที่รอส่งไป ${targetBranch})"
            return
        }

        echo "📊 [PR Manager] พบ ${commitCount} commits ใหม่ที่รอการ Promotion"

        def commitList = sh(script: "git log origin/${targetBranch}..HEAD --oneline --no-merges", returnStdout: true).trim()
        def latestSha = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
        def firstAuthor = sh(script: "git log origin/${targetBranch}..HEAD --no-merges --reverse -1 --pretty=%an", returnStdout: true).trim()
        
        // 5. สร้าง PR Body แบบละเอียด
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

        def prTitle = "[${projectName}] Promotion: ${currentEnv} -> ${nextEnv} (${commitCount} commits)"

        // 6. ตรวจสอบสถานะ PR เดิม
        def existingPR = sh(script: "gh pr list --base ${targetBranch} --head ${currentBranch} --state open --json number --jq '.[0].number'", returnStdout: true).trim()

        if (existingPR && existingPR != "null" && existingPR != "") {
            echo "🔄 พบ PR #${existingPR} อยู่แล้ว, กำลังอัปเดต..."
            sh """
                gh pr edit ${existingPR} --title "${prTitle}" --body "${prBody}"
                gh pr comment ${existingPR} --body "📌 **Jenkins Update**: พบโค้ดใหม่ (${commitCount} รายการ) พร้อมสำหรับการส่งงานต่อ"
            """
            echo "✅ อัปเดต PR #${existingPR} สำเร็จ"
        } else {
            echo "✨ กำลังสร้าง Pull Request ใหม่ไปยังกิ่ง ${targetBranch}..."
            def prUrl = sh(script: """
                gh pr create --base "${targetBranch}" \
                             --head "${currentBranch}" \
                             --title "${prTitle}" \
                             --body "${prBody}" \
                             --label "auto-promotion"
            """, returnStdout: true).trim()
            echo "✅ สร้าง PR สำเร็จ: ${prUrl}"
        }
    }
}
