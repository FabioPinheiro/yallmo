You are an expert coding assistant operating inside pi, a coding agent harness. You help users by reading files, executing commands, editing code, and writing new files.

Available tools:
- read: Read file contents
- bash: Execute bash commands (ls, grep, find, etc.)
- edit: Make precise file edits with exact text replacement, including multiple disjoint edits in one call
- write: Create or overwrite files
- subagent: For operator-requested delegation, use subagents; compose multi-child work in one workflow call.

In addition to the tools above, you may have access to other custom tools depending on the project.

Guidelines:
- Use bash for file operations like ls, rg, find
- Use read to examine files instead of cat or sed.
- You can inspect PI_* environment variables for current model and session details.
- Use edit for precise changes (edits[].oldText must match exactly)
- When changing multiple separate locations in one file, use one edit call with multiple entries in edits[] instead of multiple edit calls
- Each edits[].oldText is matched against the original file, not after earlier edits are applied. Do not emit overlapping or nested edits. Merge nearby changes into one edit.
- Keep edits[].oldText as small as possible while still being unique in the file. Do not pad with large unchanged regions.
- Use write only for new files or complete rewrites.
- Do not invoke subagents unless the operator requested delegation directly or through applicable instructions.
- Be concise in your responses
- Show file paths clearly when working with files

Pi documentation (read only when the user asks about pi itself, its SDK, extensions, themes, skills, or TUI):
- Main documentation: /usr/local/lib/node_modules/@earendil-works/pi-coding-agent/README.md
- Additional docs: /usr/local/lib/node_modules/@earendil-works/pi-coding-agent/docs
- Examples: /usr/local/lib/node_modules/@earendil-works/pi-coding-agent/examples (extensions, custom tools, SDK)
- When reading pi docs or examples, resolve docs/... under Additional docs and examples/... under Examples, not the current working directory
- When asked about: extensions (docs/extensions.md, examples/extensions/), themes (docs/themes.md), skills (docs/skills.md), prompt templates (docs/prompt-templates.md), TUI components (docs/tui.md), keybindings (docs/keybindings.md), SDK integrations (docs/sdk.md), custom providers (docs/custom-provider.md), adding models (docs/models.md), pi packages (docs/packages.md), environment variables (docs/environment-variables.md)
- When working on pi topics, read the docs and examples, and follow .md cross-references before implementing
- Always read pi .md files completely and follow links to related docs (e.g., tui.md for TUI API details)

The following skills provide specialized instructions for specific tasks.
Use the read tool to load a skills file when the task matches its description.
When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.

<available_skills>
  <skill>
    <name>hermes-context-and-memory</name>
    <description>Use for Hermes agent loop, context compression, prompt caching, memory correctness, memory_tool behavior, session search, and Pi Hermes Memory concepts.</description>
    <location>/home/fmgp/.pi/agent/skills/hermes-context-and-memory/SKILL.md</location>
  </skill>
  <skill>
    <name>hermes-prompt-assembly</name>
    <description>Use for Hermes prompt assembly: cached system layers, SOUL identity, memory/user snapshots, skills index, context files, ephemeral overlays, and cache-safe prompt design.</description>
    <location>/home/fmgp/.pi/agent/skills/hermes-prompt-assembly/SKILL.md</location>
  </skill>
  <skill>
    <name>laminar-scalajs-frontend-developer</name>
    <description>Use for Scala.js/Laminar/Airstream frontend design, implementation, refactoring, review, routing, network requests, lifecycle/ownership, CSS/SVG, forms, and tests.</description>
    <location>/home/fmgp/.pi/agent/skills/laminar-scalajs-frontend-developer/SKILL.md</location>
  </skill>
  <skill>
    <name>scala-developer</name>
    <description>Use for most Scala work: design, implementation, refactoring, review, tests, docs, build files, domain models, APIs, and idiomatic style.</description>
    <location>/home/fmgp/.pi/agent/skills/scala-developer/SKILL.md</location>
  </skill>
  <skill>
    <name>zio-proficient-developer</name>
    <description>Use for Scala ZIO/ZIO HTTP work: effects, services/layers, typed errors, resources, concurrency, streams, HTTP routes/endpoints/clients, zio-json, and tests.</description>
    <location>/home/fmgp/.pi/agent/skills/zio-proficient-developer/SKILL.md</location>
  </skill>
  <skill>
    <name>council-mode</name>
    <description>Run a bounded supervisor-mediated advisor council. Use when the user asks for council mode, asks to convene advisors, debate a decision, cross-examine recommendations, or run /council.</description>
    <location>/home/fmgp/.pi/agent/npm/node_modules/pi-subagents/skills/council-mode/SKILL.md</location>
  </skill>
  <skill>
    <name>pi-subagents</name>
    <description>Technical guidance for operator-requested delegation to builtin or custom
subagents: bounded handoffs, parallel review, scripted workflows, async work,
forked context, isolation, and coordinated execution.
</description>
    <location>/home/fmgp/.pi/agent/npm/node_modules/pi-subagents/skills/pi-subagents/SKILL.md</location>
  </skill>
  <skill>
    <name>opensrc</name>
    <description>Fetch dependency source code to give AI agents deeper implementation context. Use when the agent needs to understand how a library works internally, read source code for a package, fetch implementation details for a dependency, or explore how an npm/PyPI/crates.io package is built. Triggers include &quot;fetch source for&quot;, &quot;read the source of&quot;, &quot;how does X work internally&quot;, &quot;get the implementation of&quot;, &quot;opensrc path&quot;, or any task requiring access to dependency source code beyond types and docs.</description>
    <location>/home/fmgp/.pi/agent/npm/node_modules/pi-opensrc/skills/opensrc/SKILL.md</location>
  </skill>
</available_skills>
Current working directory: /home/fmgP








{
  "model": "gpt-5.6-terra",
  "store": false,
  "stream": true,
  "instructions": "You are an expert coding assistant operating inside pi, a coding agent harness. You help users by reading files, executing commands, editing code, and writing new files.\n\nAvailable tools:\n- read: Read file contents\n- bash: Execute bash commands (ls, grep, find, etc.)\n- edit: Make precise file edits with exact text replacement, including multiple disjoint edits in one call\n- write: Create or overwrite files\n- subagent: For operator-requested delegation, use subagents; compose multi-child work in one workflow call.\n\nIn addition to the tools above, you may have access to other custom tools depending on the project.\n\nGuidelines:\n- Use bash for file operations like ls, rg, find\n- Use read to examine files instead of cat or sed.\n- You can inspect PI_* environment variables for current model and session details.\n- Use edit for precise changes (edits[].oldText must match exactly)\n- When changing multiple separate locations in one file, use one edit call with multiple entries in edits[] instead of multiple edit calls\n- Each edits[].oldText is matched against the original file, not after earlier edits are applied. Do not emit overlapping or nested edits. Merge nearby changes into one edit.\n- Keep edits[].oldText as small as possible while still being unique in the file. Do not pad with large unchanged regions.\n- Use write only for new files or complete rewrites.\n- Do not invoke subagents unless the operator requested delegation directly or through applicable instructions.\n- Be concise in your responses\n- Show file paths clearly when working with files\n\nPi documentation (read only when the user asks about pi itself, its SDK, extensions, themes, skills, or TUI):\n- Main documentation: /usr/local/lib/node_modules/@earendil-works/pi-coding-agent/README.md\n- Additional docs: /usr/local/lib/node_modules/@earendil-works/pi-coding-agent/docs\n- Examples: /usr/local/lib/node_modules/@earendil-works/pi-coding-agent/examples (extensions, custom tools, SDK)\n- When reading pi docs or examples, resolve docs/... under Additional docs and examples/... under Examples, not the current working directory\n- When asked about: extensions (docs/extensions.md, examples/extensions/), themes (docs/themes.md), skills (docs/skills.md), prompt templates (docs/prompt-templates.md), TUI components (docs/tui.md), keybindings (docs/keybindings.md), SDK integrations (docs/sdk.md), custom providers (docs/custom-provider.md), adding models (docs/models.md), pi packages (docs/packages.md), environment variables (docs/environment-variables.md)\n- When working on pi topics, read the docs and examples, and follow .md cross-references before implementing\n- Always read pi .md files completely and follow links to related docs (e.g., tui.md for TUI API details)\n\nThe following skills provide specialized instructions for specific tasks.\nUse the read tool to load a skill's file when the task matches its description.\nWhen a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.\n\n<available_skills>\n  <skill>\n    <name>hermes-context-and-memory</name>\n    <description>Use for Hermes agent loop, context compression, prompt caching, memory correctness, memory_tool behavior, session search, and Pi Hermes Memory concepts.</description>\n    <location>/home/fmgp/.pi/agent/skills/hermes-context-and-memory/SKILL.md</location>\n  </skill>\n  <skill>\n    <name>hermes-prompt-assembly</name>\n    <description>Use for Hermes prompt assembly: cached system layers, SOUL identity, memory/user snapshots, skills index, context files, ephemeral overlays, and cache-safe prompt design.</description>\n    <location>/home/fmgp/.pi/agent/skills/hermes-prompt-assembly/SKILL.md</location>\n  </skill>\n  <skill>\n    <name>laminar-scalajs-frontend-developer</name>\n    <description>Use for Scala.js/Laminar/Airstream frontend design, implementation, refactoring, review, routing, network requests, lifecycle/ownership, CSS/SVG, forms, and tests.</description>\n    <location>/home/fmgp/.pi/agent/skills/laminar-scalajs-frontend-developer/SKILL.md</location>\n  </skill>\n  <skill>\n    <name>scala-developer</name>\n    <description>Use for most Scala work: design, implementation, refactoring, review, tests, docs, build files, domain models, APIs, and idiomatic style.</description>\n    <location>/home/fmgp/.pi/agent/skills/scala-developer/SKILL.md</location>\n  </skill>\n  <skill>\n    <name>zio-proficient-developer</name>\n    <description>Use for Scala ZIO/ZIO HTTP work: effects, services/layers, typed errors, resources, concurrency, streams, HTTP routes/endpoints/clients, zio-json, and tests.</description>\n    <location>/home/fmgp/.pi/agent/skills/zio-proficient-developer/SKILL.md</location>\n  </skill>\n  <skill>\n    <name>council-mode</name>\n    <description>Run a bounded supervisor-mediated advisor council. Use when the user asks for council mode, asks to convene advisors, debate a decision, cross-examine recommendations, or run /council.</description>\n    <location>/home/fmgp/.pi/agent/npm/node_modules/pi-subagents/skills/council-mode/SKILL.md</location>\n  </skill>\n  <skill>\n    <name>pi-subagents</name>\n    <description>Technical guidance for operator-requested delegation to builtin or custom\nsubagents: bounded handoffs, parallel review, scripted workflows, async work,\nforked context, isolation, and coordinated execution.\n</description>\n    <location>/home/fmgp/.pi/agent/npm/node_modules/pi-subagents/skills/pi-subagents/SKILL.md</location>\n  </skill>\n  <skill>\n    <name>opensrc</name>\n    <description>Fetch dependency source code to give AI agents deeper implementation context. Use when the agent needs to understand how a library works internally, read source code for a package, fetch implementation details for a dependency, or explore how an npm/PyPI/crates.io package is built. Triggers include &quot;fetch source for&quot;, &quot;read the source of&quot;, &quot;how does X work internally&quot;, &quot;get the implementation of&quot;, &quot;opensrc path&quot;, or any task requiring access to dependency source code beyond types and docs.</description>\n    <location>/home/fmgp/.pi/agent/npm/node_modules/pi-opensrc/skills/opensrc/SKILL.md</location>\n  </skill>\n</available_skills>\nCurrent working directory: /home/fmgp",
  "input": [
    {
      "role": "user",
      "content": [
        {
          "type": "input_text",
          "text": "hi"
        }
      ]
    },
    {
      "type": "message",
      "role": "assistant",
      "content": [
        {
          "type": "output_text",
          "text": "Hi! How can I help?",
          "annotations": []
        }
      ],
      "status": "completed",
      "id": "msg_0bca47b8c05fddd9016ac4118cdf0087d2b97fb6cd821745db",
      "phase": "final_answer"
    },
    {
      "role": "user",
      "content": [
        {
          "type": "input_text",
          "text": "what folder are we and what Programs I'm running"
        }
      ]
    },
    {
      "id": "rs_0bca47b8c05fddd9016ac411a3aa6c87d287a27f75b7fd78de",
      "type": "reasoning",
      "content": [],
      "encrypted_content": "gAAAAABqxBGnageGLZVhcvAG4iGQw_ZrDBLJCdp_660xqVHK1JY4YpUEFVUUQQAdkWrX6QzJgCD5AoLDyhCrJJ4XIzaz0IRzP98cf78-0hbekZD_SQWkxf6g0w8l09afzP7DWjjoxBTYqUg0tFOsacnmEQrLoOe-oFw4Ia252yeXj9v8S-dWtv74Cio9DUUXzuo4W9BMHrr62i6mkJ4vk070OHxS7vOIdCTmUlu4JUcRgrEpk7Gw7v5TTBwSK9kSGZYX0iwq6Bee7gaSP1XUfMfHXdpUDbrMLfAxkA6hfwiax_uY4Xowk-gr57uO7W52CK4M5zSbVEoxn_E4j0aVJQu1148Dyjf0ske0lR1PLCwWZL2NBojtIwtLu52LMrt6tMhSP5lr_wUCoeHSrrTvVrwgUVYsm3RqD4gqgCc82VHNJXvyNA9MKEdOQi7Xtu3H45pEFzYmMwTbO6his12wYn32HZb8gLtaEmPPhcwTBQHStbl9jaFYF1oQDD7U_Z9BPEcFrHxDlgki6rdLUkmyy-4oK2EZkE4kmnIqBzy7HwraQio5ysJt_FoQ0up8cTX6N1Dj-MEicWrd12Ih9lqGdkRcwoNNxL8VN227t9Ekx9JrVPgcYaD3t6T2Wmqn2tuNLB3AciuiCHnwQMXs5IEuPI0t2uXx7lE3_1K4tDHLQXmIab_EsCCnqbg-xlraxP8pz3yKH9A9PXy8xwRar5f5o3Kq3jUO8lkMmlKYAj584k7Ze9Deg_LjKiDYASNH78RhStuv6h3MdVV7-wz90qdcEKyPdx-rZRwa9c4SNZwhx4RyiL2NxXdnDSOKuYm3RPZLIAjlPj68z9Ti3eQf1HhyOWMRSd_b9a9145qw9DhN-Xv1In8QzkQBan_I7BBYqwoRWfgYGLdzk8IPlKLEoMetYaR_w39KP50dgLFsgBTYMqX04pc5-PYZxQBpvQtbQh5TJ07o9OlGhUJ0h2b4PL2DaP7kz0aIEXVtIh22v9fnnjr2Ky6dOkHZTIaEK_NAOsPVO0m8idmfT99Bemet6CvwZnVqtk3BC3HeV8aZu8Ar6ONUA7YOuftvMQJqFgOfgfWnJ12kcPQsG2cO9LGiRzCUaOU_Y157OeNplWBFiGg9udTM8rxAe4Rf352OmTR8o1sxWS7l5dZGMrLDFZlTu9Jocc76heaqbqS1Vut9a7ygr24IIdawxhl1djb0b3ULkdb5ntAoiAgxLEWjd_MG15jlEjxKKXlvjNFRuMi5F2BFCLfjeGqNtbTZgkgog1Aaen-PaELG7uDspHTHd4uCBQE9moD_GBLklhoUumpH9MlcUb5Ir_40FH3G-mV6SHMEfjjcDwaCYMY0q6wTgEz0XtixgeDEANaSHzOADOJTguPzLOLZyubF9F2_7-KuHr0QIbTmFO6C1d9Zjl9ybiRNmNIh6t66xm5Zrg6tyw==",
      "summary": [
        {
          "type": "summary_text",
          "text": "**Checking current directory and running processes**"
        }
      ]
    },
    {
      "type": "function_call",
      "id": "fc_0bca47b8c05fddd9016ac411a70afc87d2975ce3375dae77c1",
      "call_id": "call_OV1UjMRZ6swNPJce5GLhGn6F",
      "name": "bash",
      "arguments": "{\"command\":\"pwd && printf '\\\\nDirectory contents:\\\\n' && ls -la\",\"timeout\":10}"
    },
    {
      "type": "function_call",
      "id": "fc_0bca47b8c05fddd9016ac411a70b0c87d29db234fe96807aed",
      "call_id": "call_bciIkEdeTFZTgO5bPpuGCGQt",
      "name": "bash",
      "arguments": "{\"command\":\"printf 'Running processes for %s:\\\\n' \\\"$USER\\\"; ps -u \\\"$USER\\\" -o pid=,ppid=,stat=,etime=,comm=,args= --sort=etime\",\"timeout\":10}"
    },
    {
      "type": "function_call_output",
      "call_id": "call_OV1UjMRZ6swNPJce5GLhGn6F",
      "output": "/home/fmgp\n\nDirectory contents:\ntotal 120\ndrwxr-x--- 1 fmgp fmgp  4096 Oct  5 20:31 .\ndrwxr-xr-x 1 root root  4096 Aug 28 22:34 ..\ndrwxr-xr-x 4 fmgp fmgp   128 May 15 22:37 .agents\n-rw-r--r-- 1 fmgp fmgp   220 Mar 31  2024 .bash_logout\n-rw-r--r-- 1 fmgp fmgp  3771 Mar 31  2024 .bashrc\ndrwx------ 5 fmgp fmgp  4096 Oct  4 16:58 .cache\ndrwxrwxr-x 3 fmgp fmgp  4096 Oct  4 16:58 .config\ndrwxrwxr-x 2 fmgp fmgp  4096 Oct  4 18:02 .ivy2\ndrwxrwxr-x 4 fmgp fmgp  4096 Oct  4 16:45 .npm\ndrwxrwxr-x 3 fmgp fmgp  4096 Oct  5 10:24 .opensrc\ndrwxr-xr-x 3 fmgp fmgp    96 May 16 18:04 .pi\n-rw-r--r-- 1 fmgp fmgp   807 Mar 31  2024 .profile\ndrwxr-xr-x 1 fmgp fmgp  4096 Oct  4 16:51 .sbt\ndrwxrwxr-x 2 fmgp fmgp  4096 Oct  5 20:31 .yallmo\n-rw-rw-r-- 1 fmgp fmgp 50456 Oct  4 16:44 .zcompdump\ndrwxr-xr-x 3 fmgp fmgp    96 Oct  5 21:02 .zsh-data\n-rw-r--r-- 1 fmgp fmgp  1894 Aug 29 01:16 .zshrc\n-rw-r--r-- 1 root root   855 Oct  4 16:11 env\nlrwxrwxrwx 1 root root    22 Aug 28 22:34 workspace -> /Users/fabio/workspace\n"
    },
    {
      "type": "function_call_output",
      "call_id": "call_bciIkEdeTFZTgO5bPpuGCGQt",
      "output": "Running processes for fmgp:\n14996 14887 Rs         00:00 ps              ps -u fmgp -o pid=,ppid=,stat=,etime=,comm=,args= --sort=etime\n14887 14881 Sl+        15:51 pi              pi\n14880 14869 S          15:53 sshd            sshd: fmgp@pts/7\n14881 14880 Ss         15:53 zsh             -zsh\n14782 14698 Sl+        22:20 java            /opt/java/openjdk/bin/java --enable-native-access=ALL-UNNAMED -classpath /Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/job-3/target/640683a4/60db3d9c/yallmo_3-0.1.0-SNAPSHOT.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/fa52523a/4fcc40ef/scala3-library_3-3.9.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/b80fc48f/e9cc68dc/zio_3-2.1.24.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/24ee6abe/0d45a604/zio-http_3-3.0.1.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/96871fa1/12d04e1a/zio-json_3-0.9.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/13c5027b/f3a6a0fe/upickle_3-4.3.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/2fed7544/f589fc92/onnxruntime-1.22.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/5e61ebfe/1156f812/google-genai-1.53.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/6da62fed/ca1781bc/scala-library-3.9.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/1393e3e3/469e21d0/zio-internal-macros_3-2.1.24.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/34b3fb33/04cd0a1a/zio-stacktracer_3-2.1.24.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/b817498f/f0d080e5/izumi-reflect_3-3.0.9.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/5ceca731/543ead2c/scala-collection-compat_3-2.14.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/2bcbdb87/398f1514/zio-streams_3-2.1.24.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/d62be910/64ec658d/zio-schema_3-1.4.1.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/76172797/c3c873d3/zio-schema-json_3-1.4.1.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/b9c607c5/59e88463/zio-schema-protobuf_3-1.4.1.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/e173317a/2099660c/netty-codec-http-4.1.112.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/517e3669/e8a9bae4/netty-handler-proxy-4.1.112.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/72c7e002/2eb34eba/netty-transport-native-epoll-4.1.112.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/fd74bc51/fde9143f/netty-transport-native-epoll-4.1.112.Final-linux-x86_64.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/4e99a0a4/144b3279/netty-transport-native-epoll-4.1.112.Final-linux-aarch_64.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/e34710bc/c2c24c5a/netty-transport-native-kqueue-4.1.112.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/16c8336e/d311ba4b/netty-transport-native-kqueue-4.1.112.Final-osx-x86_64.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/c5470b8b/33c787f9/netty-transport-native-kqueue-4.1.112.Final-osx-aarch_64.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/aaf1194a/4aa6d20b/netty-incubator-transport-native-io_uring-0.0.25.Final-linux-x86_64.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/b0874133/4b79da88/magnolia_3-1.3.18.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/2fd6de72/0c83f7fb/ujson_3-4.3.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/513f49d0/073ce3b0/upack_3-4.3.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/f1eed923/e4ca16f6/upickle-implicits_3-4.3.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/1e552939/cbb2312d/google-auth-library-oauth2-http-1.33.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/eb2ae4a2/de494254/api-common-2.45.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/e44833cc/6d7efc5f/jackson-databind-2.17.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/48916cfe/1321df1d/jackson-datatype-jdk8-2.17.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/12101359/a7a4d416/jackson-datatype-jsr310-2.17.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/2ff90f49/261a3ded/Java-WebSocket-1.6.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/04642535/216e88a7/okhttp-4.12.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/ad5097a8/83558284/logging-interceptor-4.12.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/6b5ead23/b7db239d/protobuf-java-3.25.5.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/b63f0d14/3a58fd65/jspecify-1.0.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/938f59eb/07adcc0f/error_prone_annotations-2.36.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/d2ea9c6d/b2734459/izumi-reflect-thirdparty-boopickle-shaded_3-3.0.9.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/41f43e77/ec1e1e41/zio-schema-macros_3-1.4.1.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/37ac6f8c/537ca055/zio-prelude_3-1.0.0-RC28.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/15735acf/a60953b1/zio-constraintless_3-0.3.3.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/c373dee8/eeafffe4/zio-schema-derivation_3-1.4.1.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/0b61d616/ed7e7d28/netty-common-4.1.112.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/cbe94974/ef92ffe7/netty-buffer-4.1.112.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/c750265a/75d2d8e2/netty-transport-4.1.112.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/65cb6820/f56b203d/netty-codec-4.1.112.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/748f41fd/ed3a877d/netty-handler-4.1.112.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/84027d30/4a10b545/netty-codec-socks-4.1.112.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/63f86e7e/d58c0b50/netty-transport-native-unix-common-4.1.112.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/d9368f80/ebe54b03/netty-transport-classes-epoll-4.1.112.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/0dec7b06/1594e4ea/netty-transport-classes-kqueue-4.1.112.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/1d5af988/d9e892e7/netty-incubator-transport-classes-io_uring-0.0.25.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/943be5a9/dc145b94/upickle-core_3-4.3.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/e7ec4c4b/74cc79a6/gson-2.12.1.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/8baa9f20/fdc827f9/auto-value-annotations-1.11.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/a143d7d1/1dddabdd/jsr305-3.0.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/3c86beeb/b15de053/google-auth-library-credentials-1.33.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/7535ec16/3a9c3ecb/google-http-client-1.46.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/fe3fb92a/16d5960d/google-http-client-gson-1.46.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/46d9180b/3c73f474/guava-33.4.0-jre.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/ae1d95ff/adaca685/javax.annotation-api-1.3.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/7cf4127f/e4c57a4e/j2objc-annotations-3.0.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/71385833/8982e710/jackson-annotations-2.17.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/188cf45d/5f1a4ed6/jackson-core-2.17.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/ccfc5fee/93efb7f1/slf4j-api-2.0.13.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/ac015c48/563bcc76/okio-3.6.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/eed3d88d/fd873f86/kotlin-stdlib-jdk8-1.9.10.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/476a1de0/cf193fe9/zio-prelude-macros_3-1.0.0-RC28.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/1ec10d7c/c4372f62/netty-resolver-4.1.112.Final.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/f33370ef/38316204/geny_3-1.1.1.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/a5f21a2f/353296b4/httpclient-4.5.14.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/02d357de/439fb951/httpcore-4.4.16.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/0fd96ce5/a4b53c48/grpc-context-1.70.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/de120983/57f3a02d/opencensus-api-0.31.1.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/fc4d99bb/9f0c675d/opencensus-contrib-http-util-0.31.1.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/e5be5134/ad50cfa7/failureaccess-1.0.2.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/afaebef4/7525adc2/listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/6dacc9aa/8216f217/checker-qual-3.43.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/8705cf87/fcfde48f/okio-jvm-3.6.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/6717f8c9/4082e051/kotlin-stdlib-1.9.10.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/c34881f5/ed7e6052/kotlin-stdlib-jdk7-1.9.10.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/ec736c02/c3b10a0e/commons-codec-1.11.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/f6d70f58/6038b4ab/grpc-api-1.70.0.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/662b748f/ac2f2616/kotlin-stdlib-common-1.9.10.jar:/Users/fabio/workspace/yallmo/target/bg-jobs/sbt_c2f70e1b/target/948a4176/910e9f63/annotations-13.0.jar fmgp.yallmo.repl.ReplMain\n14698  8844 Sl+        22:20 sbtn-aarch64-pc /usr/share/sbt/bin/sbtn-aarch64-pc-linux --sbt-script=/usr/local/bin/sbt run\n13883 13882 Ss+        27:28 zsh             -zsh\n13882 13871 S          27:28 sshd            sshd: fmgp@pts/6\n13093     1 Sl         36:14 java            java -Dfile.encoding=UTF-8 -Dsbt.io.virtual=true -Dsbt.script=/usr/local/bin/sbt -Xms1024m -Xmx1024m -Xss4M -XX:ReservedCodeCacheSize=128m -Dscala.ext.dirs=/home/fmgp/.sbt/1.0/java9-rt-ext-eclipse_adoptium_24_0_1 -jar /usr/share/sbt/bin/sbt-launch.jar --detach-stdio\n 8844  8843 Ss      01:48:49 zsh             -zsh\n 8843  8832 S       01:48:49 sshd            sshd: fmgp@pts/4\n 7543     1 Sl      02:11:55 java            java -Dfile.encoding=UTF-8 -Dsbt.io.virtual=true -Dsbt.script=/usr/local/bin/sbt -Xms1024m -Xmx1024m -Xss4M -XX:ReservedCodeCacheSize=128m -Dscala.ext.dirs=/home/fmgp/.sbt/1.0/java9-rt-ext-eclipse_adoptium_24_0_1 -jar /usr/share/sbt/bin/sbt-launch.jar --detach-stdio\n 3772     1 Sl      14:50:25 java            java -Dfile.encoding=UTF-8 -Dsbt.io.virtual=true -Dsbt.script=/usr/local/bin/sbt -Xms1024m -Xmx1024m -Xss4M -XX:ReservedCodeCacheSize=128m -Dscala.ext.dirs=/home/fmgp/.sbt/1.0/java9-rt-ext-eclipse_adoptium_24_0_1 -jar /usr/share/sbt/bin/sbt-launch.jar --detach-stdio\n   51    24 Sl+     15:32:47 pi              pi\n   24    23 Ss      15:33:04 zsh             -zsh\n   23    12 S       15:33:04 sshd            sshd: fmgp@pts/1\n"
    },
    {
      "type": "message",
      "role": "assistant",
      "content": [
        {
          "type": "output_text",
          "text": "We’re in **`/home/fmgp`**.\n\nYou’re currently running:\n- **Pi** (two sessions)\n- **zsh** shells over **SSH** (four sessions)\n- A Scala/ZIO **Java application**: `fmgp.yallmo.repl.ReplMain` from `workspace/yallmo`\n- **sbt** background server processes (three detached Java instances)\n",
          "annotations": []
        }
      ],
      "status": "completed",
      "id": "msg_0bca47b8c05fddd9016ac411aa83ec87d2b83305b6cc7dd9c0",
      "phase": "final_answer"
    },
    {
      "role": "user",
      "content": [
        {
          "type": "input_text",
          "text": "good"
        }
      ]
    },
    {
      "type": "message",
      "role": "assistant",
      "content": [
        {
          "type": "output_text",
          "text": "👍",
          "annotations": []
        }
      ],
      "status": "completed",
      "id": "msg_0bca47b8c05fddd9016ac411e8db1087d2ae89288fec7a855c",
      "phase": "final_answer"
    },
    {
      "role": "user",
      "content": [
        {
          "type": "input_text",
          "text": "/provider-payload.ts"
        }
      ]
    },
    {
      "id": "rs_0bca47b8c05fddd9016ac411f49b9487d2b44ec2a02b64f16e",
      "type": "reasoning",
      "content": [],
      "encrypted_content": "gAAAAABqxBH1h9ZueB8OFes8h9f1Yd7VxX04egnCnvwOPcYLESLM3TeCyee2RrryKVm82DppF-woRP455U7iwSKlz2wpfgPUcV4ctl8llejP7jzCafAzlizFt8yXuW0rlwIyqKFmWMs8Q5uItjQVtMtDo6LrQJ9t1xKHdWiNHz6CXnGlEMUevteT7wRsOZmmijVID1gVTh4WRX00S8eHKtx8vFhZO9Pd0aRCE4roq0w3cHL9gAH12YJFVB3xrP2oADMipoKNlwFvxRwDPNBvpbms9G75F1-m80qlZ-dp6BFxXXpSpAahDmt7oT7B8xWFKC2igVislJIr0buLt0CuBNeaBZ-PHRhPg_G5hcfMPPVTDNopzWx5NQK3dq8LA2UhjmHxPYhUDv5weJqRBsO058fWQ-jwxiz333vVsNFrNnPmO_lAloJAp-Qga2j8jZeYPXVGntKh5275SB45KXeY82IFg-Wm_mosduEvTIC3ME8rCCsFuUTnv2F-RXLo41qbgq2c-uO82EPRm3ZfXaNYqkezFREO6o195opoOvggLNrxmTXJg17G_8ieQAs4sFV5XK5Pkcv65azq4E1DvlLAweqZgvvNao83nslmeZJPWizl4m-rhJggy6lgLOE04jKBF38-tBn5lL1zBKZ-Hf2YBnwdXGUyY7NUWrIRu5gXn4ZOqF4v-q54-VvYkx0wWKWgEcRhDhcnUorXFBigYRlng1ZRVhZvUbbnMDtsu7xYeLPVeixMDfOR7jPH03pObFDmfOPv1UaAa8WNZpArSjC6j0aY9klWQfaALU4jLIB8JFhKwOBLWzn86mrz4WIm7qO3T76VspYUeac-dOtEpCasaxrnjtFV_M_4IODN_KIx2YtjhX7A4E2ZerUN0HxLoJ4yaBnpDSGYsdxOP5tzlf-2KBkSkj2RI2ou8STsBfyDwEHBcN2BWVMey4uCgSv8CVpNPTzzsTMnLU9TinZuk9XOM2mW3XrSzFO8JLsd0QvC9KQ1gbVzq2aNo83mcDSkIzaRBYuRf7XQY5HjGK1RNao-tXGzjysgQPOMcYnQLp-FTjxlzUmJEb0dAL7dsk5SQEUbzF0sFDN3DJlmk3JLM4j786ZwoEZ3PUkRi8KGScy8Uf3441ZuTwjUDjLPcGEqzJC6TkjAywPmmXe9m-Bl0QiQ0be9Bf6K0HOTPwrR67g3-eA8BWmbZkh_kJMaqy7O_idfordHmaLah6plIYLEHpW_AHnGHo9blrTe-5w1b7PQqVa_K6gGoOygkqNQmckuTuRS2ztKuZV_z95xOdrJjGX8hhrWyNn3Z6BSc-bZDiHTmGJlb7_RMw8LOJvYLK8BCG0CZgnBugmLnTKpf9tq0lFIkOaY3CODTglIlIg5jAiqWxyakuUmRPNRQhB9rNdqxCA8Xd7GcLMhXNTrEFNgH6KuaLkxM9_oLYX6an1igBu8oZBwJq2aG5HANvfbVRKo2NKrxsnYFK2blmGC",
      "summary": [
        {
          "type": "summary_text",
          "text": "**Searching for target file**"
        }
      ]
    },
    {
      "type": "function_call",
      "id": "fc_0bca47b8c05fddd9016ac411f5d9c087d2ad8c2ebda97accf5",
      "call_id": "call_6hNUAdaiT0BiAOCdt6l5qEzH",
      "name": "bash",
      "arguments": "{\"command\":\"find . -name 'provider-payload.ts' -type f -print 2>/dev/null\",\"timeout\":10}"
    },
    {
      "type": "function_call_output",
      "call_id": "call_6hNUAdaiT0BiAOCdt6l5qEzH",
      "output": "./.pi/agent/extensions/provider-payload.ts\n./.opensrc/repos/github.com/earendil-works/pi/0.84.4/packages/coding-agent/examples/extensions/provider-payload.ts\n"
    },
    {
      "type": "function_call",
      "id": "fc_0bca47b8c05fddd9016ac411f9577887d28e05cffd9afa330d",
      "call_id": "call_u5jX8Q7mJzPd86v8rPmZSLuJ",
      "name": "read",
      "arguments": "{\"path\":\"/home/fmgp/.pi/agent/extensions/provider-payload.ts\"}"
    },
    {
      "type": "function_call_output",
      "call_id": "call_u5jX8Q7mJzPd86v8rPmZSLuJ",
      "output": "// ~/.pi/agent/extensions/provider-payload.ts\nimport { appendFileSync } from \"node:fs\";\nimport { join } from \"node:path\";\nimport type { ExtensionAPI } from \"@earendil-works/pi-coding-agent\";\nexport default function (pi: ExtensionAPI) {\n  pi.on(\"before_provider_request\", (event, ctx) => {\n    appendFileSync(\n      join(process.env.HOME!, \".pi\", \"agent\", \"provider-payload.log\"),\n      `${JSON.stringify(event.payload, null, 2)}\\n\\n`,\n      \"utf8\",\n    );\n  });\n}"
    }
  ],
  "text": {
    "verbosity": "low"
  },
  "include": [
    "reasoning.encrypted_content"
  ],
  "prompt_cache_key": "01a10de4-7b00-7385-bb4c-f49555ad8684",
  "tool_choice": "auto",
  "parallel_tool_calls": true,
  "tools": [
    {
      "type": "function",
      "name": "read",
      "description": "Read the contents of a file. Supports text files and images (jpg, png, gif, webp, bmp). Images are sent as attachments. For text files, output is truncated to 2000 lines or 50KB (whichever is hit first). Use offset/limit for large files. When you need the full file, continue with offset until complete.",
      "parameters": {
        "type": "object",
        "required": [
          "path"
        ],
        "properties": {
          "path": {
            "type": "string",
            "description": "Path to the file to read (relative or absolute)"
          },
          "offset": {
            "type": "number",
            "description": "Line number to start reading from (1-indexed)"
          },
          "limit": {
            "type": "number",
            "description": "Maximum number of lines to read"
          }
        }
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "bash",
      "description": "Execute a bash command in the current working directory. Returns stdout and stderr. Output is truncated to last 2000 lines or 50KB (whichever is hit first). If truncated, full output is saved to a temp file. Optionally provide a timeout in seconds.",
      "parameters": {
        "type": "object",
        "required": [
          "command"
        ],
        "properties": {
          "command": {
            "type": "string",
            "description": "Shell command to execute"
          },
          "timeout": {
            "type": "number",
            "description": "Timeout in seconds (optional, no default timeout)"
          }
        }
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "edit",
      "description": "Edit a single file using exact text replacement. Every edits[].oldText must match a unique, non-overlapping region of the original file. If two changes affect the same block or nearby lines, merge them into one edit instead of emitting overlapping edits. Do not include large unchanged regions just to connect distant changes.",
      "parameters": {
        "type": "object",
        "required": [
          "path",
          "edits"
        ],
        "properties": {
          "path": {
            "type": "string",
            "description": "Path to the file to edit (relative or absolute)"
          },
          "edits": {
            "type": "array",
            "items": {
              "type": "object",
              "required": [
                "oldText",
                "newText"
              ],
              "properties": {
                "oldText": {
                  "type": "string",
                  "description": "Exact text for one targeted replacement. It must be unique in the original file and must not overlap with any other edits[].oldText in the same call."
                },
                "newText": {
                  "type": "string",
                  "description": "Replacement text for this targeted edit."
                }
              }
            },
            "description": "One or more targeted replacements. Each edit is matched against the original file, not incrementally. Do not include overlapping or nested edits. If two changes touch the same block or nearby lines, merge them into one edit instead."
          }
        }
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "write",
      "description": "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. Automatically creates parent directories.",
      "parameters": {
        "type": "object",
        "required": [
          "path",
          "content"
        ],
        "properties": {
          "path": {
            "type": "string",
            "description": "Path to the file to write (relative or absolute)"
          },
          "content": {
            "type": "string",
            "description": "Content to write to the file"
          }
        }
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_close",
      "description": "Close the page",
      "parameters": {
        "type": "object",
        "properties": {},
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_resize",
      "description": "Resize the browser window",
      "parameters": {
        "type": "object",
        "properties": {
          "width": {
            "type": "number",
            "description": "Width of the browser window"
          },
          "height": {
            "type": "number",
            "description": "Height of the browser window"
          }
        },
        "required": [
          "width",
          "height"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_console_messages",
      "description": "Returns all console messages",
      "parameters": {
        "type": "object",
        "properties": {
          "level": {
            "default": "info",
            "description": "Level of the console messages to return. Each level includes the messages of more severe levels. Defaults to \"info\".",
            "type": "string",
            "enum": [
              "error",
              "warning",
              "info",
              "debug"
            ]
          },
          "all": {
            "description": "Return all console messages since the beginning of the session, not just since the last navigation. Defaults to false.",
            "type": "boolean"
          },
          "filename": {
            "description": "Filename to save the console messages to. If not provided, messages are returned as text.",
            "type": "string"
          }
        },
        "required": [
          "level"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_handle_dialog",
      "description": "Handle a dialog",
      "parameters": {
        "type": "object",
        "properties": {
          "accept": {
            "type": "boolean",
            "description": "Whether to accept the dialog."
          },
          "promptText": {
            "description": "The text of the prompt in case of a prompt dialog.",
            "type": "string"
          }
        },
        "required": [
          "accept"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_evaluate",
      "description": "Evaluate JavaScript expression on page or element",
      "parameters": {
        "type": "object",
        "properties": {
          "element": {
            "description": "Human-readable element description used to obtain permission to interact with the element",
            "type": "string"
          },
          "target": {
            "description": "Exact target element reference from the page snapshot, or a unique element selector",
            "type": "string"
          },
          "function": {
            "type": "string",
            "description": "() => { /* code */ } or (element) => { /* code */ } when element is provided"
          },
          "filename": {
            "description": "Filename to save the result to. If not provided, result is returned as text.",
            "type": "string"
          }
        },
        "required": [
          "function"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_file_upload",
      "description": "Upload one or multiple files",
      "parameters": {
        "type": "object",
        "properties": {
          "paths": {
            "description": "The absolute paths to the files to upload. Can be single file or multiple files. If omitted, file chooser is cancelled.",
            "type": "array",
            "items": {
              "type": "string"
            }
          }
        },
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_drop",
      "description": "Drop files or MIME-typed data onto an element, as if dragged from outside the page. At least one of \"paths\" or \"data\" must be provided.",
      "parameters": {
        "type": "object",
        "properties": {
          "element": {
            "description": "Human-readable element description used to obtain permission to interact with the element",
            "type": "string"
          },
          "target": {
            "type": "string",
            "description": "Exact target element reference from the page snapshot, or a unique element selector"
          },
          "paths": {
            "description": "Absolute paths to files to drop onto the element.",
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "data": {
            "description": "Data to drop, as a map of MIME type to string value (e.g. {\"text/plain\": \"hello\", \"text/uri-list\": \"https://example.com\"}).",
            "type": "object",
            "propertyNames": {
              "type": "string"
            },
            "additionalProperties": {
              "type": "string"
            }
          }
        },
        "required": [
          "target"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_fill_form",
      "description": "Fill multiple form fields",
      "parameters": {
        "type": "object",
        "properties": {
          "fields": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "element": {
                  "description": "Human-readable element description used to obtain permission to interact with the element",
                  "type": "string"
                },
                "target": {
                  "type": "string",
                  "description": "Exact target element reference from the page snapshot, or a unique element selector"
                },
                "name": {
                  "type": "string",
                  "description": "Human-readable field name"
                },
                "type": {
                  "type": "string",
                  "enum": [
                    "textbox",
                    "checkbox",
                    "radio",
                    "combobox",
                    "slider"
                  ],
                  "description": "Type of the field"
                },
                "value": {
                  "type": "string",
                  "description": "Value to fill in the field. If the field is a checkbox, the value should be `true` or `false`. If the field is a combobox, the value should be the text of the option."
                }
              },
              "required": [
                "target",
                "name",
                "type",
                "value"
              ],
              "additionalProperties": false
            },
            "description": "Fields to fill in"
          }
        },
        "required": [
          "fields"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_press_key",
      "description": "Press a key on the keyboard",
      "parameters": {
        "type": "object",
        "properties": {
          "key": {
            "type": "string",
            "description": "Name of the key to press or a character to generate, such as `ArrowLeft` or `a`"
          }
        },
        "required": [
          "key"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_type",
      "description": "Type text into editable element",
      "parameters": {
        "type": "object",
        "properties": {
          "element": {
            "description": "Human-readable element description used to obtain permission to interact with the element",
            "type": "string"
          },
          "target": {
            "type": "string",
            "description": "Exact target element reference from the page snapshot, or a unique element selector"
          },
          "text": {
            "type": "string",
            "description": "Text to type into the element"
          },
          "submit": {
            "description": "Whether to submit entered text (press Enter after)",
            "type": "boolean"
          },
          "slowly": {
            "description": "Whether to type one character at a time. Useful for triggering key handlers in the page. By default entire text is filled in at once.",
            "type": "boolean"
          }
        },
        "required": [
          "target",
          "text"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_navigate",
      "description": "Navigate to a URL",
      "parameters": {
        "type": "object",
        "properties": {
          "url": {
            "type": "string",
            "description": "The URL to navigate to"
          }
        },
        "required": [
          "url"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_navigate_back",
      "description": "Go back to the previous page in the history",
      "parameters": {
        "type": "object",
        "properties": {},
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_network_requests",
      "description": "Returns a numbered list of network requests since loading the page. Use browser_network_request with the number to get full details.",
      "parameters": {
        "type": "object",
        "properties": {
          "static": {
            "default": false,
            "description": "Whether to include successful static resources like images, fonts, scripts, etc. Defaults to false.",
            "type": "boolean"
          },
          "filter": {
            "description": "Only return requests whose URL matches this regexp (e.g. \"/api/.*user\").",
            "type": "string"
          },
          "filename": {
            "description": "Filename to save the network requests to. If not provided, requests are returned as text.",
            "type": "string"
          }
        },
        "required": [
          "static"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_network_request",
      "description": "Returns full details (headers and body) of a single network request, or a single part if `part` is set. Use the number from browser_network_requests.",
      "parameters": {
        "type": "object",
        "properties": {
          "index": {
            "type": "integer",
            "minimum": 1,
            "maximum": 9007199254740991,
            "description": "1-based index of the request, as printed by browser_network_requests."
          },
          "part": {
            "description": "Return only this part of the request. Omit to return full details.",
            "type": "string",
            "enum": [
              "request-headers",
              "request-body",
              "response-headers",
              "response-body"
            ]
          },
          "filename": {
            "description": "Filename to save the result to. If not provided, output is returned as text.",
            "type": "string"
          }
        },
        "required": [
          "index"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_run_code_unsafe",
      "description": "Run a Playwright code snippet. Unsafe: executes arbitrary JavaScript in the Playwright server process and is RCE-equivalent.",
      "parameters": {
        "type": "object",
        "properties": {
          "code": {
            "description": "A JavaScript function containing Playwright code to execute. It will be invoked with a single argument, page, which you can use for any page interaction. For example: `async (page) => { await page.getByRole('button', { name: 'Submit' }).click(); return await page.title(); }`",
            "type": "string"
          },
          "filename": {
            "description": "Load code from the specified file. If both code and filename are provided, code will be ignored.",
            "type": "string"
          }
        },
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_take_screenshot",
      "description": "Take a screenshot of the current page. You can't perform actions based on the screenshot, use browser_snapshot for actions.",
      "parameters": {
        "type": "object",
        "properties": {
          "element": {
            "description": "Human-readable element description used to obtain permission to interact with the element",
            "type": "string"
          },
          "target": {
            "description": "Exact target element reference from the page snapshot, or a unique element selector",
            "type": "string"
          },
          "type": {
            "default": "png",
            "description": "Image format for the screenshot. Default is png.",
            "type": "string",
            "enum": [
              "png",
              "jpeg"
            ]
          },
          "filename": {
            "description": "File name to save the screenshot to. Defaults to `page-{timestamp}.{png|jpeg}` if not specified. Prefer relative file names to stay within the output directory.",
            "type": "string"
          },
          "fullPage": {
            "description": "When true, takes a screenshot of the full scrollable page, instead of the currently visible viewport. Cannot be used with element screenshots.",
            "type": "boolean"
          }
        },
        "required": [
          "type"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_snapshot",
      "description": "Capture accessibility snapshot of the current page, this is better than screenshot",
      "parameters": {
        "type": "object",
        "properties": {
          "target": {
            "description": "Exact target element reference from the page snapshot, or a unique element selector",
            "type": "string"
          },
          "filename": {
            "description": "Save snapshot to markdown file instead of returning it in the response.",
            "type": "string"
          },
          "depth": {
            "description": "Limit the depth of the snapshot tree",
            "type": "number"
          },
          "boxes": {
            "description": "Include each element's bounding box as [box=x,y,width,height] in the snapshot. Coordinates are viewport-relative, in CSS pixels (Element.getBoundingClientRect)",
            "type": "boolean"
          }
        },
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_click",
      "description": "Perform click on a web page",
      "parameters": {
        "type": "object",
        "properties": {
          "element": {
            "description": "Human-readable element description used to obtain permission to interact with the element",
            "type": "string"
          },
          "target": {
            "type": "string",
            "description": "Exact target element reference from the page snapshot, or a unique element selector"
          },
          "doubleClick": {
            "description": "Whether to perform a double click instead of a single click",
            "type": "boolean"
          },
          "button": {
            "description": "Button to click, defaults to left",
            "type": "string",
            "enum": [
              "left",
              "right",
              "middle"
            ]
          },
          "modifiers": {
            "description": "Modifier keys to press",
            "type": "array",
            "items": {
              "type": "string",
              "enum": [
                "Alt",
                "Control",
                "ControlOrMeta",
                "Meta",
                "Shift"
              ]
            }
          }
        },
        "required": [
          "target"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_drag",
      "description": "Perform drag and drop between two elements",
      "parameters": {
        "type": "object",
        "properties": {
          "startElement": {
            "description": "Human-readable source element description used to obtain the permission to interact with the element",
            "type": "string"
          },
          "startTarget": {
            "type": "string",
            "description": "Exact target element reference from the page snapshot, or a unique element selector"
          },
          "endElement": {
            "description": "Human-readable target element description used to obtain the permission to interact with the element",
            "type": "string"
          },
          "endTarget": {
            "type": "string",
            "description": "Exact target element reference from the page snapshot, or a unique element selector"
          }
        },
        "required": [
          "startTarget",
          "endTarget"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_hover",
      "description": "Hover over element on page",
      "parameters": {
        "type": "object",
        "properties": {
          "element": {
            "description": "Human-readable element description used to obtain permission to interact with the element",
            "type": "string"
          },
          "target": {
            "type": "string",
            "description": "Exact target element reference from the page snapshot, or a unique element selector"
          }
        },
        "required": [
          "target"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_select_option",
      "description": "Select an option in a dropdown",
      "parameters": {
        "type": "object",
        "properties": {
          "element": {
            "description": "Human-readable element description used to obtain permission to interact with the element",
            "type": "string"
          },
          "target": {
            "type": "string",
            "description": "Exact target element reference from the page snapshot, or a unique element selector"
          },
          "values": {
            "type": "array",
            "items": {
              "type": "string"
            },
            "description": "Array of values to select in the dropdown. This can be a single value or multiple values."
          }
        },
        "required": [
          "target",
          "values"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_tabs",
      "description": "List, create, close, or select a browser tab.",
      "parameters": {
        "type": "object",
        "properties": {
          "action": {
            "type": "string",
            "enum": [
              "list",
              "new",
              "close",
              "select"
            ],
            "description": "Operation to perform"
          },
          "index": {
            "description": "Tab index, used for close/select. If omitted for close, current tab is closed.",
            "type": "number"
          },
          "url": {
            "description": "URL to navigate to in the new tab, used for new.",
            "type": "string"
          }
        },
        "required": [
          "action"
        ],
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "playwright_browser_wait_for",
      "description": "Wait for text to appear or disappear or a specified time to pass",
      "parameters": {
        "type": "object",
        "properties": {
          "time": {
            "description": "The time to wait in seconds",
            "type": "number"
          },
          "text": {
            "description": "The text to wait for",
            "type": "string"
          },
          "textGone": {
            "description": "The text to wait for to disappear",
            "type": "string"
          }
        },
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "additionalProperties": false
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "mcp",
      "description": "MCP gateway - connect to MCP servers and call their tools.\n\nDirect tools available (call as normal tools): playwright (23)\n\nUsage:\n  mcp({ })                              → Show server status\n  mcp({ server: \"name\" })               → List tools from server\n  mcp({ search: \"query\" })              → Search for tools (MCP + pi, space-separated words OR'd)\n  mcp({ describe: \"tool_name\" })        → Show tool details and parameters\n  mcp({ connect: \"server-name\" })       → Connect to a server and refresh metadata\n  mcp({ tool: \"name\", args: '{\"key\": \"value\"}' })    → Call a tool (args is JSON string)\n\nMode: tool (call) > connect > describe > search > server (list) > nothing (status)",
      "parameters": {
        "type": "object",
        "properties": {
          "tool": {
            "type": "string",
            "description": "Tool name to call (e.g., 'xcodebuild_list_sims')"
          },
          "args": {
            "type": "string",
            "description": "Arguments as JSON string (e.g., '{\"key\": \"value\"}')"
          },
          "connect": {
            "type": "string",
            "description": "Server name to connect (lazy connect + metadata refresh)"
          },
          "describe": {
            "type": "string",
            "description": "Tool name to describe (shows parameters)"
          },
          "search": {
            "type": "string",
            "description": "Search tools by name/description"
          },
          "regex": {
            "type": "boolean",
            "description": "Treat search as regex (default: substring match)"
          },
          "includeSchemas": {
            "type": "boolean",
            "description": "Include parameter schemas in search results (default: true)"
          },
          "server": {
            "type": "string",
            "description": "Filter to specific server (also disambiguates tool calls)"
          }
        }
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "subagent",
      "description": "Delegate one child with {agent,task?}; otherwise choose exactly one of {workflowScript,args?}, {workflowScriptPath,args?} or {workflow,args}. agent/task exclude workflow inputs; task excludes action. agent may target management actions. action is management/control; validate accepts either script without launching. workflowScriptPath loads from request cwd before sandbox execution.\nScripts: JavaScript statement bodies with explicit return, top-level await, plain helpers/Promise chains; nested async function/arrow/method helpers are rejected. Await runs.run('key',{agent,task}) before .output; await runs.all([{key,agent,task},...]) for an ordered array, not a key map. Observe every stored run promise with direct await, Promise.race or Promise.all. Await/return runs.steer(key,message,options?) for a prior key, never raw run ids; queued/delivered/missed/failed receipts are not compliance proof.\nBefore advanced orchestration (runs.lanes, rolling fanout, mission state, handoffs), read {action:\"guide\",topic:\"workflows\"} or the pi-subagents skill. Raw-script sandboxes add deeply frozen args; all sandboxes provide runs, emit, console, JavaScript and enabled mission state, with no filesystem/shell/Pi tools/host globals. External CLI agents support native options only when their runner declares them; read guide tool-reference before passing model, structured output, acceptance/agentContract, tool budget, fast, fork context or skills/tools.\nModel override: first call {action:\"models\"}; copy exact provider/id, not agent names. Thinking uses model suffix, not watchdog-only thinking.\nNamed resources: {workflow:'review',args:{task:'...'}} or {workflow:'run-ci',args:{command:'npm test'}}. Raw scripts also accept bounded plain-data args; raw-script args persist as evidence, so never include secrets. worktree:true requires clean source; baseRef defaults to HEAD at allocation or a supported named ref, never full 40/64-character commit IDs or revision expressions.\n\nSAFETY-CRITICAL SUBAGENT GUIDANCE:\n• Direct parent execution is the default. Invoke subagents only when delegation is authorized by the operator's current request or applicable user/project instructions; task size, complexity, risk, tool-call count, or recipe fit do not independently authorize delegation.\n• First call {action:\"list\",capabilities:true}: executable, non-disabled agents only; external-cli requires runner.available === true. Passive PATH/PATHEXT/X_OK is not authentication/version/launch proof; preflight is authoritative.\n• Workflow, child launch, prompt runtime, extension load or child tooling failure is a lane infrastructure blocker. Stop; report exact failure, run/status and repo/cwd/worktree/branch/ref; verify clean worktree or capture partial diff before same-protocol retry or asking the owner. Never silently switch to interactive_shell, pi -ne, Codex/Claude/Cursor CLI or foreground/external mode: governed-workflow fallback requires explicit owner approval, not Pi core's generic pi -ne hint. Explicit foreground/CLI requests and work outside that protocol remain valid.\n• Omit action for execution. For an authorized delegated multi-step/parallel workflow: exactly one top-level subagent workflow call with async:true; children launch only inside it.\n• Async follows asyncByDefault (normally true); async:false only to block the parent, not for final reviews/gates. Consume results at dependency barriers. Native async completion wakes this session: return control, no sleep/poll or bg_wait merely for a wake. bg_wait is for provider/detached work without native notification needing a same-turn result.\n• Ordinary child subagents are not orchestrators; only configured fanout within depth/session limits. For an authorized delegated workflow, keep one writer per cwd/worktree and isolate concurrent writers. Use fresh-context read-only reviewers when independent review was requested, then parent synthesis/fixes. Oracle/advisor unknowns use supervisor dialogue; one-shot only when requested.\n• Bind durable output on runs.run/runs.all, not task filename prose; return actual outputReference/outputPathMapping/artifactPaths, evidence and residual risks.\n• children.list is workflow-only, not an exhaustive list of direct native children: resume only resumable rows. When an intended child's exact run id is known, inspect it with {action:\"status\",id}; if status identifies the candidate, attempt {action:\"resume\",id,message}. Resume authoritatively checks eligibility, may reject it, and otherwise detaches a follow-up/challenge with the stored agent/model/tool contract. Use a labeled same-role fallback only when no known candidate exists or resume rejects eligibility. Scripts await runs.run(newKey,{resume:runId,task}); continue from latest returned runId. Each distinct resume pass needs a new stable key; same-key reuse requires identical launch parameters.\n• Named resources own authority; raw workflowScript/workflowScriptPath cannot use runs.host. Granted commands/relative outputs use workflow cwd, never per-step cwd.\n• Inspect asyncId/asyncDir (status.json, events.jsonl, logs) with status/debug.run; control with interrupt/stop/resume/steer. Read {action:\"guide\",topic:\"tool-reference\"} for controls/evidence gates.",
      "parameters": {
        "type": "object",
        "properties": {
          "agent": {
            "type": "string",
            "description": "One-child agent or management target."
          },
          "task": {
            "type": "string",
            "description": "One-child task; requires agent."
          },
          "extensionBindings": {
            "type": "object",
            "maxProperties": 16,
            "additionalProperties": true,
            "description": "Child-only bounded JSON; namespaces package.name/1."
          },
          "action": {
            "type": "string",
            "minLength": 1,
            "description": "Management/control only; omit for execution. validate accepts either script input. Discover actions with guide topic tool-reference."
          },
          "capabilities": {
            "type": "boolean",
            "description": "list: compact capability rows/details without system prompts."
          },
          "name": {
            "type": "string",
            "description": "schedule.create name."
          },
          "id": {
            "type": "string",
            "description": "Run id/prefix for status/control."
          },
          "runId": {
            "type": "string",
            "description": "Target run ID; prefer id."
          },
          "dir": {
            "type": "string",
            "description": "Async directory for status/control."
          },
          "handoffPath": {
            "type": "string",
            "description": "Existing manifest for worktree/lane actions."
          },
          "repo": {
            "type": "string",
            "description": "worktree.cleanup repo; default cwd."
          },
          "planId": {
            "type": "string",
            "description": "Reserved; cleanup is plan-only."
          },
          "laneId": {
            "type": "string",
            "minLength": 1,
            "maxLength": 128,
            "description": "Exact manifest run id for lane actions."
          },
          "merge": {
            "type": "object",
            "additionalProperties": true,
            "description": "lane.recordMerge evidence; read guide tool-reference."
          },
          "supersession": {
            "type": "object",
            "additionalProperties": true,
            "description": "lane.recordSupersession evidence; read guide tool-reference."
          },
          "index": {
            "type": "integer",
            "minimum": 0,
            "description": "Zero-based child/transcript index."
          },
          "childId": {
            "type": "string",
            "minLength": 1,
            "maxLength": 256,
            "description": "Child-scoped stop identity."
          },
          "view": {
            "type": "string",
            "enum": [
              "fleet",
              "transcript"
            ],
            "description": "status view: fleet overview or transcript tail with id/dir and optional index."
          },
          "lines": {
            "type": "integer",
            "minimum": 1,
            "maximum": 500,
            "description": "Transcript tail lines; default 80."
          },
          "topic": {
            "type": "string"
          },
          "message": {
            "type": "string",
            "description": "resume/steer guidance or project.open prompt."
          },
          "mode": {
            "type": "string",
            "enum": [
              "steer",
              "follow_up",
              "auto",
              "plan",
              "apply"
            ],
            "description": "steer delivery mode; worktree.cleanup supports plan only, no apply/removal."
          },
          "steeringRecovery": {
            "type": "boolean",
            "description": "steer: pause/revive after missed acknowledgment; default true in direct steer mode, forced false by extension RPC for exact ownership."
          },
          "additional": {
            "type": "integer",
            "minimum": 1,
            "description": "grant-spawn-budget: root interactive parent + native user confirmation only; total grants capped at original configured cap."
          },
          "scope": {
            "type": "string",
            "enum": [
              "session",
              "user",
              "project"
            ],
            "description": "watchdog.configure scope; default session, persistent only if explicit."
          },
          "target": {
            "type": "string",
            "enum": [
              "main",
              "children",
              "child"
            ],
            "description": "Watchdog target."
          },
          "focus": {
            "type": "boolean",
            "description": "Focus inspector.open/project.open pane."
          },
          "thinking": {
            "anyOf": [
              {
                "type": "string"
              },
              {
                "type": "boolean"
              }
            ],
            "description": "watchdog.configure only: off/minimal/low/medium/high/xhigh/max, inherit, false=off; true invalid. Dispatch ignores this; use model suffix."
          },
          "at": {
            "type": "string",
            "description": "schedule.create: delay (+10m) or zoned ISO timestamp."
          },
          "every": {
            "type": "string",
            "description": "schedule.create interval, e.g. 30m/6h/2d/2w."
          },
          "sessionOnly": {
            "type": "boolean"
          },
          "quiet": {
            "type": "boolean"
          },
          "on": {
            "anyOf": [
              {
                "type": "string"
              },
              {
                "type": "integer"
              }
            ],
            "description": "Reserved calendar selector."
          },
          "timezone": {
            "type": "string"
          },
          "overlap": {
            "type": "string",
            "enum": [
              "skip"
            ]
          },
          "catchUp": {
            "type": "string",
            "enum": [
              "none",
              "latest"
            ],
            "description": "Missed schedule occurrences; default latest."
          },
          "missionId": {
            "type": "string"
          },
          "mission": {
            "anyOf": [
              {
                "type": "object",
                "additionalProperties": true
              },
              {
                "type": "boolean"
              }
            ],
            "description": "false disables; true invalid. Object: exactly one non-empty title or summary; objective/labels optional; goal only true, requires budget.tokens."
          },
          "missionUpdate": {
            "type": "object",
            "additionalProperties": true,
            "description": "Mission patch; read guide missions."
          },
          "missionStatus": {
            "type": "string"
          },
          "missionScope": {
            "type": "string",
            "description": "project (default) or global pointer index."
          },
          "runMode": {
            "type": "string",
            "description": "Attached run mode."
          },
          "runStatus": {
            "type": "string",
            "description": "Attached run status."
          },
          "summary": {
            "type": "string",
            "description": "Mission close summary."
          },
          "config": {
            "anyOf": [
              {
                "type": "object",
                "additionalProperties": true
              },
              {
                "type": "string"
              }
            ],
            "description": "create/update agent config; object or JSON string."
          },
          "workflow": {
            "type": "string",
            "minLength": 1,
            "description": "Extension-owned workflow resource."
          },
          "args": {
            "type": "object",
            "maxProperties": 16,
            "additionalProperties": true,
            "description": "Bounded plain-JSON args for named, inline, or file-backed workflows; raw-script args are exposed deeply frozen and persisted, so do not include secrets."
          },
          "workflowScript": {
            "type": "string",
            "minLength": 1,
            "description": "Inline JavaScript statement body; raw/unknown provenance, no runs.host. Use explicit return and top-level await; see tool guidance/guide workflows."
          },
          "workflowScriptPath": {
            "type": "string",
            "minLength": 1,
            "description": "Raw script file; host reads from request cwd before sandbox. Mutually exclusive with workflowScript and workflow."
          },
          "globalConcurrencyLimit": {
            "type": "integer",
            "minimum": 1
          },
          "maxSubagentSpawnsPerRun": {
            "type": "integer",
            "minimum": 1
          },
          "preflight": {
            "type": "object",
            "required": [
              "version",
              "lanes"
            ],
            "properties": {
              "version": {
                "type": "integer",
                "minimum": 1,
                "maximum": 1
              },
              "coverage": {
                "type": "string",
                "enum": [
                  "complete",
                  "partial"
                ]
              },
              "lanes": {
                "type": "array",
                "items": {
                  "type": "object",
                  "required": [
                    "key"
                  ],
                  "properties": {
                    "key": {
                      "type": "string",
                      "minLength": 1,
                      "maxLength": 128
                    },
                    "mode": {
                      "type": "string",
                      "enum": [
                        "mutation",
                        "review",
                        "scout",
                        "gate"
                      ]
                    },
                    "decision": {
                      "type": "string",
                      "maxLength": 256
                    },
                    "claims": {
                      "type": "array",
                      "items": {
                        "type": "string",
                        "maxLength": 256
                      },
                      "maxItems": 16
                    },
                    "expectedOutput": {
                      "type": "string",
                      "maxLength": 256
                    },
                    "independence": {
                      "type": "string",
                      "maxLength": 256
                    }
                  },
                  "additionalProperties": false
                },
                "maxItems": 64
              }
            },
            "additionalProperties": false,
            "description": "Display-only lane hints; coverage mismatches warn, never change authority/execution."
          },
          "chatProgress": {
            "type": "string",
            "enum": [
              "auto",
              "off",
              "live-card"
            ],
            "description": "auto: live card only for watched foreground in same Git repository. live-card requires same-repo async:false; async: omit or auto/off."
          },
          "isolation": {
            "type": "string",
            "enum": [
              "none",
              "worktree"
            ],
            "description": "Shared cwd or managed git worktrees."
          },
          "worktree": {
            "type": "boolean",
            "description": "Isolate each workflow child in a managed git worktree; child worktree:false overrides default."
          },
          "baseRef": {
            "type": "string"
          },
          "lane": {
            "type": "object",
            "required": [
              "version",
              "key"
            ],
            "properties": {
              "version": {
                "type": "integer",
                "minimum": 1,
                "maximum": 1
              },
              "key": {
                "type": "string",
                "minLength": 1,
                "maxLength": 128,
                "pattern": "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"
              },
              "mode": {
                "type": "string",
                "enum": [
                  "mutation",
                  "review",
                  "scout",
                  "gate"
                ]
              },
              "sourceRef": {
                "type": "string",
                "minLength": 1,
                "maxLength": 128
              },
              "claims": {
                "type": "array",
                "items": {
                  "type": "string",
                  "minLength": 1,
                  "maxLength": 160
                },
                "maxItems": 20
              },
              "outputPaths": {
                "type": "array",
                "items": {
                  "type": "string",
                  "minLength": 1,
                  "maxLength": 256
                },
                "maxItems": 10
              }
            },
            "additionalProperties": false,
            "description": "Display/triage only; sourceRef is opaque, never resolved by status."
          },
          "context": {
            "type": "string",
            "enum": [
              "fresh",
              "fork",
              "profile"
            ],
            "description": "fresh/fork overrides every child; profile requires agent's declared defaultContext, ignoring config. Omitted: defaultSubagentContext wins over each agent defaultContext; implicit fork needs persisted parent + leaf, else fresh. forkContext may prune forks before spawn."
          },
          "async": {
            "type": "boolean",
            "description": "Background; default asyncByDefault. false only to block parent."
          },
          "timeoutMs": {
            "type": "integer",
            "minimum": 1,
            "description": "Timeout. Foreground and single async runs use config timeoutMs, else 30m; async composites have no default parent deadline. Alias maxRuntimeMs."
          },
          "maxRuntimeMs": {
            "type": "integer",
            "minimum": 1,
            "description": "Alias timeoutMs (same defaults)."
          },
          "checkpointBeforeDeadlineMs": {
            "type": "integer",
            "minimum": 1,
            "maximum": 2147483647,
            "description": "Async single-agent runs only: the runner requests that the child checkpoint and stop this many ms before the run deadline (best-effort; the deadline kill still applies)."
          },
          "toolTimeoutMs": {
            "type": "integer",
            "minimum": 1,
            "description": "Per-tool deadline (ms); fast builtins default 5m."
          },
          "toolBudget": {
            "type": "object",
            "required": [
              "hard"
            ],
            "properties": {
              "soft": {
                "type": "integer",
                "minimum": 1
              },
              "hard": {
                "type": "integer",
                "minimum": 1
              },
              "block": {
                "anyOf": [
                  {
                    "type": "array",
                    "minItems": 1,
                    "items": {
                      "type": "string",
                      "minLength": 1
                    }
                  },
                  {
                    "type": "string",
                    "enum": [
                      "*"
                    ]
                  }
                ]
              }
            },
            "additionalProperties": false,
            "description": "soft nudges; after hard, block tools (default read/grep/find/ls, '*' for all) so child can finalize."
          },
          "usageBudget": {
            "type": "object",
            "properties": {
              "tokens": {
                "type": "object",
                "required": [
                  "hard"
                ],
                "properties": {
                  "soft": {
                    "type": "number",
                    "exclusiveMinimum": 0
                  },
                  "hard": {
                    "type": "number",
                    "exclusiveMinimum": 0
                  }
                },
                "additionalProperties": false
              },
              "costUsd": {
                "type": "object",
                "required": [
                  "hard"
                ],
                "properties": {
                  "soft": {
                    "type": "number",
                    "exclusiveMinimum": 0
                  },
                  "hard": {
                    "type": "number",
                    "exclusiveMinimum": 0
                  }
                },
                "additionalProperties": false
              }
            },
            "additionalProperties": false,
            "description": "Root-only reported usage; hard prevents later launches. Running children are not stopped."
          },
          "agentScope": {
            "type": "string",
            "description": "user/project/both (default); project wins collisions."
          },
          "cwd": {
            "type": "string",
            "description": "Execution/project-pane directory."
          },
          "machine": {
            "type": "string",
            "minLength": 1,
            "maxLength": 128,
            "description": "Herdr saved machine id or label; runs an external CLI agent there. cwd then means the directory on that machine."
          },
          "artifacts": {
            "type": "boolean",
            "description": "Debug artifacts; default true."
          },
          "includeProgress": {
            "type": "boolean",
            "description": "Full result progress; default false."
          },
          "share": {
            "type": "boolean",
            "description": "Upload session to GitHub Gist; default false."
          },
          "sessionDir": {
            "type": "string",
            "description": "Session log directory; default temp, independent of share."
          },
          "control": {
            "type": "object",
            "properties": {
              "enabled": {
                "type": "boolean"
              },
              "needsAttentionAfterMs": {
                "type": "integer",
                "minimum": 1
              },
              "activeNoticeAfterMs": {
                "type": "integer",
                "minimum": 1
              },
              "activeNoticeAfterTurns": {
                "type": "integer",
                "minimum": 1
              },
              "activeNoticeAfterTokens": {
                "type": "integer",
                "minimum": 1
              },
              "failedToolAttemptsBeforeAttention": {
                "type": "integer",
                "minimum": 1
              },
              "notifyOn": {
                "type": "array",
                "items": {
                  "type": "string",
                  "enum": [
                    "active_long_running",
                    "needs_attention"
                  ]
                }
              },
              "notifyChannels": {
                "type": "array",
                "items": {
                  "type": "string",
                  "enum": [
                    "event",
                    "async",
                    "intercom"
                  ]
                }
              }
            }
          },
          "output": {
            "anyOf": [
              {
                "type": "string"
              },
              {
                "type": "boolean"
              }
            ],
            "description": "Child output path or false; relative workflow paths use managed artifact routing. Bind durable output here, not task prose; return outputReference/outputPathMapping/artifactPaths."
          },
          "outputMode": {
            "type": "string",
            "enum": [
              "inline",
              "file-only"
            ],
            "description": "Default inline; file-only requires output path."
          },
          "skill": {
            "anyOf": [
              {
                "type": "array",
                "items": {
                  "type": "string"
                }
              },
              {
                "type": "boolean"
              },
              {
                "type": "string"
              }
            ],
            "description": "Skills: names/CSV/array; false disables, true uses default."
          },
          "model": {
            "type": "string",
            "description": "Child model provider/id; bare id only if unique. Suffix :off/minimal/low/medium/high/xhigh/max overrides agent thinking default."
          },
          "fast": {
            "type": "boolean",
            "description": "Native OpenAI-Codex priority tier; default false, may cost more/quota."
          },
          "outputSchema": {
            "anyOf": [
              {
                "type": "object",
                "additionalProperties": true
              },
              {
                "type": "boolean"
              }
            ],
            "description": "Structured output schema override; false disables an agent default."
          },
          "agentContract": {
            "type": "object",
            "required": [
              "version"
            ],
            "properties": {
              "version": {
                "type": "integer",
                "minimum": 1,
                "maximum": 1
              }
            },
            "additionalProperties": false,
            "description": "Compatibility behavior. Omit for the default behavior."
          },
          "acceptance": {
            "anyOf": [
              {
                "type": "string",
                "enum": [
                  "auto",
                  "attested",
                  "checked"
                ]
              },
              {
                "type": "string",
                "enum": [
                  "reviewed"
                ],
                "deprecated": true
              },
              {
                "type": "string",
                "pattern": "^\\s*\\{"
              },
              {
                "type": "boolean"
              },
              {
                "type": "object",
                "additionalProperties": true
              }
            ],
            "description": "Evidence policy; omit for read-only/review. false disables; true invalid. Prefer object; see guide tool-reference for levels, evidence and review.required."
          },
          "gate": {
            "anyOf": [
              {
                "type": "string",
                "minLength": 1
              },
              {
                "type": "object",
                "properties": {
                  "command": {
                    "type": "string",
                    "minLength": 1
                  },
                  "output": {
                    "type": "string",
                    "enum": [
                      "json"
                    ]
                  },
                  "schema": {
                    "type": "object"
                  },
                  "timeoutMs": {
                    "type": "integer",
                    "minimum": 1
                  }
                },
                "required": [
                  "command"
                ],
                "additionalProperties": false
              }
            ],
            "description": "Host gate command run after the child finishes: a string, or { command, output: \"json\", schema?, timeoutMs? } whose passing stdout becomes structuredOutput (not with outputSchema). Cannot be combined with acceptance; an explicit acceptance of false is treated as omitted."
          }
        }
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "bg_wait",
      "description": "Wait for background, provider, or detached work that has no native completion notification, then return.\n\nOrdinary async subagent runs already notify this session natively when they complete or need attention. In an interactive chat, return control instead of calling this merely to wait. Use this tool for provider jobs, remembered detached foreground runs, or other background work without a native notification path. Headless runs auto-drain current-session subagent work at agent_end; use this tool only when the current turn must receive non-notifying background work results.\n\n• { } — return when the first initially active async run or registered provider item finishes, or when a subagent needs attention.\n• { all: true } — wait for every async run, provider item, and remembered detached foreground descendant that was active when the call began.\n• { id: \"...\" } — wait for one async or remembered detached foreground subagent run (id or prefix). Named async runs that already finished return their terminal result references.\n• { id: \"...\", nonBlocking: true } — resolve the prefix once, persist an exact-run wake subscription, and return immediately. Use this for detached work without native completion delivery; the originating interactive session wakes on completion, failure, attention, reconciliation failure, or timeout.\n• { stopOnAttention: false } — for blocking waits only, keep waiting through idle or long-thinking attention; supervisor/contact requests still stop the wait.\n• { timeoutMs: 600000 } — stop waiting after N ms; active work keeps running. Omitted values use waitTool.defaultTimeoutMs, then 30 minutes. Window expiry returns a non-error window_elapsed result with active work identities.\n\nNon-blocking subscriptions are visible in subagent status and differ from disabling waitTool: waitTool.enabled=false returns immediately without registering any future wake. Provider jobs are session-scoped and identified exactly, so replacing one job with another cannot hide a completion. Provider extensions must be explicitly loaded in this process. In a child agent, keep `bg_wait` in the child tool allowlist and load each provider through the agent's extensions or subagentOnlyExtensions; this tool never loads providers or grants tools itself.",
      "parameters": {
        "type": "object",
        "properties": {
          "id": {
            "type": "string",
            "description": "Async run or remembered detached foreground run id/prefix to wait for one specific run. Ordinary async subagent runs already notify this session natively; use bg_wait for provider, detached, or other background work without native notification, or when same-turn blocking results are truly needed. Omit to wait across every active async run started in this session only when a same-turn wait is truly needed."
          },
          "nonBlocking": {
            "type": "boolean",
            "description": "When true, resolve id to one exact run, persist a wake subscription, and return immediately. Use this only for provider, detached, or other background work without a native completion notification; ordinary async subagent runs already notify this session natively and do not need a subscription. The originating session is woken on completion, failure, attention, reconciliation failure, or timeout. Requires id and cannot be combined with all."
          },
          "all": {
            "type": "boolean",
            "description": "Wait for ALL active runs to finish. Ordinary async subagent runs already notify this session natively; use all only when a same-turn result from tracked background work is truly needed. Default false: return when the first tracked run or provider item finishes or needs attention. Ignored when id targets a single run."
          },
          "timeoutMs": {
            "type": "integer",
            "minimum": 1,
            "description": "Give up waiting after this many milliseconds (the runs keep going regardless). Ordinary async subagent runs already notify this session natively; use a wait timeout only when same-turn results are truly needed for provider, detached, or other background work without native notification. Defaults to config waitTool.defaultTimeoutMs, then 1800000 (30 minutes). Window expiry is a non-error active-work result."
          },
          "stopOnAttention": {
            "type": "boolean",
            "description": "For a blocking wait that is truly needed, stop when a run needs attention by default. Set false to keep waiting through idle or long-thinking attention; supervisor/contact requests still stop the wait."
          }
        }
      },
      "strict": null
    },
    {
      "type": "function",
      "name": "subagent_supervisor",
      "description": "Native pi-subagents supervisor channel. Use reply/pending/status to answer child subagent requests without overriding pi-intercom.",
      "parameters": {
        "type": "object",
        "required": [
          "action"
        ],
        "properties": {
          "action": {
            "type": "string",
            "enum": [
              "list",
              "pending",
              "status",
              "reply"
            ]
          },
          "to": {
            "type": "string"
          },
          "message": {
            "type": "string"
          },
          "replyTo": {
            "type": "string"
          }
        },
        "additionalProperties": false
      },
      "strict": null
    }
  ],
  "reasoning": {
    "effort": "high",
    "summary": "auto"
  }
}