plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
val testRuntime = sourceSets["test"].runtimeClasspath
tasks.register<JavaExec>("selfCheck") { dependsOn("testClasses"); classpath = testRuntime; mainClass.set("com.screenwordpulse.core.SelfCheckKt"); jvmArgs("-Dfile.encoding=UTF-8") }
tasks.register<JavaExec>("demo") { dependsOn("testClasses"); classpath = testRuntime; mainClass.set("com.screenwordpulse.core.DemoKt"); jvmArgs("-Dfile.encoding=UTF-8") }
tasks.named("check") { dependsOn("selfCheck") }
