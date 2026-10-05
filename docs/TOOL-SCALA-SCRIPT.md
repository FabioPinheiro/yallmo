# TOOL SCALA SCRIPT

Create a tool that allowed to create and run scala code in one shot.
Review [THe scala days notes for inspiration](./scala_agent_orchestration_inspiration.md)

Review the scala 3 capture-checking
- [ATC: A Minimal Agent With Tracked Capabilities](https://github.com/lampepfl/atc)
- [Scala 3 Capture Checking](https://docs.scala-lang.org/scala3/reference/experimental/capture-checking/index.html)
  - [Capture Checking Basics](https://docs.scala-lang.org/scala3/reference/experimental/capture-checking/basics.html)
  - [Safe Mode](https://docs.scala-lang.org/scala3/reference/experimental/capture-checking/safe.html)
- [](https://dl.acm.org/doi/10.1145/3786335.3813127)

## bash example 


```bash
echo 'println("Hello World")' | scala run _.sc

# if JVM 25 use java-opt
scala run _.sc --java-opt=--sun-misc-unsafe-memory-access=allow --java-opt=--enable-native-access=ALL-UNNAMED <<'EOF'
val x = 10
val y = 20
println(x + y)
EOF
```

## FUTURE (Not now)
### SANDBOX


