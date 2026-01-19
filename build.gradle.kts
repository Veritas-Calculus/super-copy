// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Force secure versions of transitive dependencies to fix vulnerability alerts
subprojects {
    configurations.all {
        resolutionStrategy {
            // Netty vulnerabilities (CVE-2025-55163, CVE-2025-58057, CVE-2025-67735, etc.)
            force("io.netty:netty-codec:4.1.129.Final")
            force("io.netty:netty-codec-http:4.1.129.Final")
            force("io.netty:netty-codec-http2:4.1.129.Final")
            force("io.netty:netty-handler:4.1.129.Final")
            force("io.netty:netty-common:4.1.129.Final")
            
            // Guava vulnerability (CVE-2023-2976)
            force("com.google.guava:guava:33.4.0-jre")
            
            // Protobuf vulnerability (CVE-2024-7254)
            force("com.google.protobuf:protobuf-java:4.29.3")
            
            // Commons Compress vulnerabilities
            force("org.apache.commons:commons-compress:1.27.1")
            
            // JDOM2 XXE vulnerability
            force("org.jdom:jdom2:2.0.6.1")
            
            // jose4j DoS vulnerability
            force("org.bitbucket.b_c:jose4j:0.9.7")
        }
    }
}