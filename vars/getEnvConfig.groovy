/**
 * Centralized Environment Configuration for Shared Library
 * Usage: def config = getEnvConfig('DEV')
 */
def call(String envName) {
    def envs = [
        'DEV': [
            host         : '192.168.0.126',
            user         : 'Administrator',
            sshCredId    : 'windows-ssh-key',
            sqlHost      : '27.254.239.245',
            sqlPort      : '33433',
            dbCredId     : 'sql-dev-creds',
            gitCredId    : 'AdaDevSecOps',
            sqlBackupPath: 'C:/X-BACKUP/SQL'
        ],
        'SIT': [
            host         : '192.168.0.127', // ตัวอย่าง
            user         : 'Administrator',
            sshCredId    : 'windows-ssh-key-sit',
            sqlHost      : '27.254.239.245',
            sqlPort      : '33433',
            dbCredId     : 'sql-sit-creds',
            gitCredId    : 'AdaDevSecOps',
            sqlBackupPath: 'C:/X-BACKUP/SQL'
        ],
        'PROD': [
            host         : '192.168.0.200', // ตัวอย่าง
            user         : 'Administrator',
            sshCredId    : 'windows-ssh-key-prod',
            sqlHost      : '27.254.239.245',
            sqlPort      : '33433',
            dbCredId     : 'sql-prod-creds',
            gitCredId    : 'AdaDevSecOps',
            sqlBackupPath: 'C:/X-BACKUP/SQL'
        ]
    ]

    def config = envs[envName.toUpperCase()]
    if (!config) {
        error "❌ Environment '${envName}' not found in getEnvConfig.groovy. Available: ${envs.keySet()}"
    }
    return config
}
