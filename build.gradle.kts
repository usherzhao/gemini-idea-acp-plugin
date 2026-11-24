import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "com.geminicode.acp"
version = "1.2.3"

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
              <li><b>完整集成官方 Antigravity CLI (<code>agy acp</code>) 全套协议能力</b>：支持 <code>oauth-personal</code> / 企业账号 / API Key 认证、官方 ACP <code>logout</code> 一键切换账号、<code>--sandbox</code>、<code>--disable-telemetry</code> 与 <code>--agent</code> 选项。</li>
              <li><b>多模态图片输入与官方 <code>embeddedContext</code></b>：支持输入框 <code>Ctrl+V</code> 粘贴截图、拖拽或选取图片发送标准 ACP <code>image</code> 块，以及自动附带 <code>resource</code> 结构化文件/行区间上下文。</li>
              <li><b><code>@</code> 模糊搜索文件与 <code>/</code> Skills 快捷补全</b>：支持文件名、驼峰首字母与跨段路径模糊搜索；项目树/标签页/导航栏/编辑器右键直接引用文件或行号（对话框内仅显示精简文件名+行号）。</li>
              <li><b>多目录关联工作区与系统提示词追加</b>：支持官方 <code>additionalDirectories</code> 跨项目联调（同步放行安全白名单）与 <code>_meta.antigravity.systemPrompt.append</code>（支持自动加载 <code>.gemini/rules.md</code>）。</li>
              <li><b>会话全生命周期管理与实时上下文用量</b>：支持 <code>session/list</code>、<code>session/load</code>（完整回放）、<code>session/resume</code>（快速续聊）、<code>session/close</code>（优雅释放资源）、自定义重命名及 <code>usage_update</code> 实时用量显示。</li>
              <li><b>官方动态模型/思考强度选择与安全 Diff 确认</b>：底部动态获取 <code>model</code> / <code>thought_level</code> / <code>mode</code>，修改或新建文件前自动弹出 IDEA 原生语法高亮 Diff 对比窗。</li>
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
