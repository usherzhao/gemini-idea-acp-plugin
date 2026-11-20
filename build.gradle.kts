import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "com.geminicode.acp"
version = "1.0.10"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdeaCommunity("2025.2")
        testFramework(TestFrameworkType.Platform)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginConfiguration {
        id = "com.geminicode.acp"
        name = "Gemini Code (ACP)"
        version = project.version.toString()
        description = """
            <p>Gemini Code (ACP) for IntelliJ IDEA - OAuth Personal AI Coding Assistant.</p>
            <p>基于官方 <b>Antigravity CLI (<code>agy acp</code>) / Gemini CLI ACP 协议</b> 的 IntelliJ IDEA 插件，支持通过 <code>oauth-personal</code>（个人 Google 账号）登录并进行对话式代码编辑。</p>
            <ul>
              <li><b>自动适配 Antigravity CLI (<code>agy acp</code>) 与一键切换账号</b>：优先调用官方 <code>agy acp</code> 服务，完整支持 <code>oauth-personal</code> 登录与官方 ACP <code>logout</code> 退出/切换账号重新登录。</li>
              <li><b><code>@</code> 模糊搜索文件与 <code>/</code> Skills 快捷补全</b>：对话框内输入 <code>@</code> 支持文件名、驼峰首字母与路径模糊搜索并引用文件，输入 <code>/</code> 模糊选择已安装的 Skills 与斜杠命令。</li>
              <li><b>右键一键引用文件与行号位置</b>：支持在项目树、编辑器标签页、导航栏右键直接将文件/目录引用带入对话框，以及在编辑器内右键仅传递选中起止行号（不粘贴源码）。</li>
              <li><b>官方动态模型与思考强度选择</b>：底部直接从官方 ACP <code>configOptions</code> 动态获取模型、思考强度（<code>thought_level</code>）及执行模式。</li>
              <li><b>聊天对话历史管理与重命名</b>：支持浏览、搜索、筛选、自定义修改对话名称并一键回放续聊历史对话。</li>
              <li><b>安全 Diff 预览确认</b>：AI 修改或新建文件前自动弹出 IDEA 原生语法高亮 Diff 对比窗，确认后通过撤销栈安全写入。</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }

    buildSearchableOptions = false
}

tasks {
    withType<JavaCompile> {
        options.release.set(21)
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    test {
        useJUnit()
    }
}
