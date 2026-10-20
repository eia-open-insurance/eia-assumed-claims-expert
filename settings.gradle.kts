rootProject.name = "eia-assumed-claims-expert"

includeBuild("../eia-platform") {
    dependencySubstitution {
        substitute(module("com.eia:libs")).using(project(":libs"))
    }
}
