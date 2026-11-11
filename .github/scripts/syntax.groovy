import org.codehaus.groovy.control.*
// Parse-only check: lexing, parsing and AST conversion. Skips class resolution,
// because Hubitat classes (device, interfaces, hubitat.*) are not available here.
int failed = 0
args.each { path ->
    def cu = new CompilationUnit()
    cu.addSource(new File(path))
    try {
        cu.compile(Phases.CONVERSION)
        println "PASS ${path}"
    } catch (e) {
        failed++
        println "FAIL ${path}\n${e.message}"
    }
}
System.exit(failed ? 1 : 0)
