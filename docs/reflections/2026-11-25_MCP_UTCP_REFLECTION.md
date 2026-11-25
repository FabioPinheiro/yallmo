# Giving my agent access to the outside world: exploring MCP and UTCP

*Working narrative for a LinkedIn post, updated throughout the interview. Implementation experience, current opinions, and future ideas are distinguished below; a shorter publishable version can follow.*

## The starting point

I wanted to give the agent I'm developing the ability to call external systems.

That practical need led me to explore two approaches: MCP, which seemed to be what everyone was using, and a newer alternative I had come across called the Universal Tool Calling Protocol (UTCP).

The starting point wasn't a conclusion about which protocol was better. It was a capability I wanted to build into my agent.

## I wasn't starting from zero with MCP

I had already built MCP servers for custom CLI tooling I'd developed. Some of that tooling was complex, and the point of the MCP integration was to help the AI understand how to call it effectively.

So when I started thinking about external tools for YALLMO, I was almost certain I would implement MCP server and client support again. I was also thinking about how to lazy-load the available tools.

## Reusing an integration versus building one

What appealed to me about MCP was the ecosystem. If a service already offered an MCP integration, I wanted my agent to be able to use it.

But building an MCP component myself felt like a different proposition. Why create and maintain another component just to call a service? If I was writing the integration anyway, I could make it a native extension of my agent's harness. For my own use case, I didn't necessarily need a standardized protocol in between.

That distinction mattered to me: consuming an existing MCP integration wasn't the same decision as building and maintaining one myself.

## What caught my attention about UTCP

My understanding of UTCP was that it acted like a manual: a description of what an API does and how to call it. It seemed to offer a similar tool-discovery idea without requiring me to build a separate protocol-specific component around the service.

That was the appeal. If an API already existed, I wanted to describe how to use it rather than build another component just to expose it to my agent.

## Questioning what we needed: we already had Scala mode

I started investigating how to add MCP to YALLMO, using Pi's approach as a reference. At the same time, I was comparing that integration with UTCP.

Then I questioned the premise: did we need a dedicated integration like that at all?

YALLMO already had Scala mode. The model could generate code, and that code could call an API. What it needed was to understand the API: what it does and how to call it. Maybe a skill-like description was enough.

That was how I understood the appeal of a UTCP manual. Other API descriptions might serve a similar purpose, but I wanted to try UTCP specifically.

## From generated code to one concise tool

There were still practical details to handle, including authentication. I was thinking about ZIO HTTP for making the calls, and much of the request-handling work seemed mechanical and repetitive.

So the idea became: create a tool to handle that work and give it the manual. In effect, I had arrived back at UTCP—but as a concise tool inside the harness.

I asked the AI to implement it that way. In the flow we built, the model first calls the tool to discover the available manuals, then uses those descriptions to work out what it can call. The System 1 idea would be a future alternative for introducing the relevant tools, not something this implementation already did.

The implementation was surprisingly straightforward. It needed a few tweaks and bug fixes in the implementation—not fixes to limitations I had encountered in UTCP itself—but then it was working well enough that my reaction was: let's keep it.

## What I actually tested

This is still a very recent experiment. So far, I've tried public weather APIs and authenticated GitHub API calls, passing a token in an HTTP request header.

Those initial calls worked almost on the first try, with a minimal amount of code. The implementation took less than an hour, including my review. And when I say I implemented it, the AI did the coding; I directed the work and reviewed the result.

That's the scope of my experience so far: a small implementation and a few working API integrations, not a production-readiness assessment or a comprehensive comparison of MCP and UTCP. But the effort needed to get to that point was small enough that I wanted to keep the approach.

## What this implementation doesn't cover

My UTCP implementation is deliberately limited: HTTP only, with either no authentication or token/API-key authentication supplied through an HTTP request header, as in the GitHub test. That describes what I've implemented, not the limits of UTCP as a protocol.

I'd like to explore other transports, such as WebSockets and gRPC, and additional authentication models. None of that was necessary for the question I was testing: could I quickly create a manual and use it to call an API in a simple use case?

This was an experiment, not an attempt to build comprehensive UTCP support. The aim was simple and fast: describe the API, create the manual, and use it.

## The practical workflow: documentation → manual → call

For a straightforward API integration, the workflow is now simple: give the model the provider's API documentation, ask it to create a UTCP manual, then ask it to make the call.

We have a skill for creating those manuals, and that skill was also created by AI. The appeal is how quickly I can get an integration working. The manual only needs to be created once, then reused and potentially improved later.

That also expands what retrospective analysis could do. It wouldn't have to stop at classifying failures: it could suggest improvements to the manuals or the skills themselves. That's a future possibility, not something we've automated already.

## A separate concern: where the tool server runs

Protocol choice wasn't my only concern. I didn't want to rely on MCP over standard input/output; I wanted HTTP so the MCP server could run separately from the harness.

With my own tooling, I know what I've implemented. A third-party MCP server is different: it's executable software, and I don't want to run it alongside the harness on the same machine just because I want to use its tools. Extensions raise a similar concern; this isn't unique to MCP.

One deployment option I considered was running Docker inside the Docker sandbox, with the MCP server in its own container and its ports exposed to the sandbox. The intention was to create an isolation boundary between the tool server and the harness—not simply to give the agent another way to make calls.

This was an architecture I was considering, not yet a claim about what we implemented or what security guarantees it provided.

## Lazy loading meant context, not processes

When I talked about lazy-loading tools, I meant controlling what the model sees—not starting tool servers on demand.

The analogy was skills: make a name and a short description available, but keep the full instructions out of context until the model asks for them. I didn't want every conversation to start with a large body of instructions about how to call tools it might never need.

## Beyond discovery: just-in-time tools

Longer term, I want to go further. Even a list of names and descriptions becomes overhead when there are enough tools. Why tell the model about every available tool when the conversation only concerns one specific area?

My idea is to use a cheaper “System 1” model to decide when tools are relevant and which ones to inject, just in time for the main LLM to use them. Rather than always exposing the whole catalog, the harness would make relevant tools available as the conversation moves into an area where they are useful.

There is a caching trade-off here. Changing the available tools or adding tool instructions can affect prompt-cache reuse, so I need to balance keeping existing context stable against introducing new tools. My current preference is to accept that cost when a tool becomes relevant rather than pay the context cost of carrying everything from the beginning—even if “everything” is only names and descriptions.

This is a direction I want to explore, not an implemented result or a measured performance conclusion.

## What I'd like beyond a description

One thing I'd like is a dedicated place for skill-like instructions, separate from the description. For me, describing what a tool does isn't always enough to teach the model how to use it.

I'd like to be able to supply those instructions and inject them when relevant, in the same spirit as loading a skill. This is feedback about what I'd like from the experience, rather than a claim that I've established a limitation of the UTCP specification.

## Could the manual itself be lazy-loaded?

That raises another question for me: how much of a manual does the model need up front? In the current implementation, asking for a manual loads the whole manual. Maybe discovery isn't the only thing that should be lazy; perhaps field descriptions and output-schema details could be loaded on demand too.

If a response contains a person's `age` as a number, the model will probably interpret it as age in years. Often that assumption will be right. But the name alone doesn't guarantee the meaning: a number might represent days since birth, or a boolean field might indicate whether someone meets an age threshold. Types help, but they don't fully explain semantics.

I'm wondering whether the model could inspect a response first and request more information about fields only when needed, instead of receiving every type and long description in advance. That's a hypothesis, not evidence that omitting those details is reliable. A plausible interpretation can still be wrong, and the model might not realize it needs to ask.

## Could retrospective review improve what we inject?

Another possibility is to review a conversation afterward, checking whether the model's assumptions matched the API's meaning. Over time, those reviews could provide evidence about which details are useful and which omissions repeatedly cause mistakes.

If a particular manual or missing explanation keeps causing problems, perhaps the system could automatically include more guidance the next time that manual is requested. I'd like to explore whether this feedback loop could improve context selection without always loading everything. It isn't an implemented learning mechanism yet.

The motivation is consistent: keep context as small as practical. More context means more information for the model to keep track of, more opportunities for mistakes, higher token costs, and potentially slower responses. What I haven't established is where the savings stop being worth the ambiguity, or how much context a reliable review process would itself require.

## More trust, more freedom—but trust needs evidence

Where I'd draw the line depends on how much confidence I have in the AI and how destructive the tools can be. More trust can mean more freedom, but I want that trust to be grounded in metrics rather than intuition alone.

I don't have that measurement system yet. What I do have is stored conversation history. I'd like to use it for retrospective analysis, potentially by another agent, to build a clearer picture of:

- Which tool calls fail.
- What caused each failure.
- How often the same cause recurs.
- How long each tool call takes.

From there, I could build tables, categorize failures, and analyze the patterns before deciding what to change. That could inform how much guidance to inject and how much freedom to give the model. It wouldn't, by itself, establish that every successful call had been interpreted correctly.

The approach I want is a cycle: record what happens, measure it, review the evidence, and then draw conclusions. My early tests make the small UTCP tool worth keeping; they don't yet establish the right level of autonomy or the minimum reliable context.

## An open question: backend state and durability

Another concern is how stateful APIs interact with durable execution. Some APIs keep state in the backend, and I see potential complications when combining that with a durability model. Stateless calls would be appealing here.

But I haven't thought this through enough to draw a conclusion. It's an open design question—not a failure I observed in the initial tests, and not necessarily something UTCP itself should solve.

## Why I still want MCP client support

I still want MCP client support in YALLMO, exposed as a tool. Its popularity and the integrations already available are the main reasons. I don't have to think it's a great protocol in itself to recognize the value of that ecosystem.

The distinction is who maintains the integration and who it is for. If someone else already maintains an MCP server I can use, that's useful. If I'm building something just for myself, I'd rather create the custom integration I need than maintain an MCP server around it.

If I'm building a tool for other people, the calculation changes again. Exposing it through MCP could make sense because so many clients already support it. More integrations make the ecosystem more useful, and wider adoption makes publishing another integration more worthwhile.

I'm also interested in the direction of richer integrations—not just a list of APIs the model can call, but capabilities involving skills, user interfaces, and task execution. That could make MCP valuable for more than access to an existing ecosystem.

## Let the model direct the work, without carrying all the data

A concrete example is passing a file to a tool. If the model has to read the entire file and reproduce its contents as a tool argument, we're spending context on moving data. The model might only need to decide which file to send and what operation to perform; the harness could transfer the contents without routing them through the model's context.

Another example is a long-running task. I'd like it to run in the background, let the conversation continue, and notify the agent when it completes, rather than keep the interaction waiting for a synchronous tool result.

Or a tool could supply the skill-like instructions needed to use it, making the relevant guidance available when the capability is introduced.

Those are the kinds of capabilities that make a richer protocol interesting to me. Where I'd otherwise need a custom harness extension, standardized support could make that functionality reusable across clients. That's a different value proposition from simply wrapping an API.

My understanding is that MCP is evolving in this direction. These examples explain what interests me; they aren't features I evaluated in this implementation.

*Publication check: verify the current specification and client support for file/resource transfer, background tasks and completion notifications, skills or instructions, and UI integrations. Distinguish existing features, proposed additions, and client-specific behavior; don't describe MCP as only an API definition or imply these capabilities are all new.*

## Where I've landed—for now

This wasn't a dramatic change in my thinking. It mostly reinforced my approach: have an idea, try it, and see whether it works. It's still too soon to know how good the choice will be over time.

I started expecting to add MCP; now I'm thinking I may not need it for straightforward API calls. A reusable manual and a small calling tool seem good enough for what I've tried so far.

For connecting to an existing API, I'd prefer the provider to describe how to call it rather than require an extra MCP server just to expose it to an agent. From the client's perspective, the approaches feel very similar to me: discover what is available, understand how to call it, and make the call. The extra component is what makes me question which approach I need.

That doesn't mean MCP has no place. I still want access to its existing ecosystem, and an MCP server can encapsulate complex flows rather than merely wrap an endpoint. An API provider could also offer an interface that makes those operations straightforward for an AI model.

If I need something more complex or tightly controlled—Git tooling, for example—I'd consider building a native extension for exactly what I need, tightly integrated with YALLMO. That extension can manage complex flows, internal state, durability, or whatever else the capability requires. Complexity alone doesn't make me want to put a standardized protocol between it and my own harness.

My conclusion isn't that MCP is bad or that UTCP wins in every situation. For the simple HTTP API calls I've tried, the manual-driven tool is enough for now. For capabilities where I want tight control, I can build an extension. To consume existing integrations—or share a tool with a wider audience—MCP still makes sense. These serve different needs, and the longer-term judgment still needs evidence.

For this experiment, I wanted to give the model API documentation, create a manual, and make the call. Simple and fast. That's what I tested, and so far, it works.
