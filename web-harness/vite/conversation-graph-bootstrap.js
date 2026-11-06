import { createConversationGraph } from "./conversation-graph.js";

// Installed before the Scala.js entry point. Only this bridge owns the Cytoscape DOM subtree.
globalThis.yallmoConversationGraph = { create: createConversationGraph };
