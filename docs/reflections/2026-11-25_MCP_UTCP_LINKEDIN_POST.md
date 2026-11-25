I'm building YALLMO, an experimental AI-agent harness and orchestrator. It's my personal lab for exploring tools, context management, and how agents work together. For the last few months, I've been learning and experimenting with AI full-time. I'm not an AI specialist; I'm developing my skills by building, trying things, and following what others are doing. There are plenty of different views out there. This is just mine, based on what I've tried so far.

Recently, I wanted to give the agents in YALLMO a way to call external services.

My first thought was MCP. I'd already built MCP servers for some custom CLI tools, mostly to help the model understand how to use them. With so many integrations already available, I expected to add MCP support again.

While looking into that, I came across UTCP, the Universal Tool Calling Protocol. What caught my attention was the manual: a description of an API and how to call it, without having to build a separate server around it.

That got me thinking about what I actually needed.

YALLMO already has a Scala mode where the model can write and run code. It could already make an API call. It just needed to know how. But I didn't want it writing the same request and authentication code every time.

So I asked the AI to implement a small UTCP tool. Discover the available manuals, read the relevant one, and make the call.

There were a few implementation bugs to fix, but it worked almost on the first try. The whole thing took less than an hour, including my review. The AI did the coding; I directed the work and reviewed it.

So far, I've only tried public weather APIs and authenticated GitHub calls, with a token in an HTTP header. My implementation only supports HTTP and simple token authentication. It's a small experiment, not full UTCP support.

We also created a skill for writing the manuals. Now I can give the model some API documentation, ask it to create a manual, and then ask it to use it. The manual can be reused afterward.

I'm still thinking about how much of that information needs to be in context. I'd like to load instructions only when they're useful, rather than start every conversation with every tool and its documentation. Eventually, I'd like to review stored conversations to see what failed, why, and whether the manuals need improving. I don't have those measurements yet.

I still want MCP client support. There are plenty of existing integrations I'd like to use, and MCP would also make sense if I wanted to share a tool with other people.

But if I'm building something just for YALLMO, I don't necessarily want another server to maintain. For a simple API call, this manual-based tool has been enough so far. For something where I want tighter control, I might write a native extension instead.

Nothing dramatic changed here. I had an idea, tried it, and got a few simple calls working with very little code. For now, I'm keeping it and seeing how it goes.
