[API Error: {"error":{"code":400,"message":"API Key not found. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID","domain":"googleapis.com","metadata":{"service":"generativelanguage.googleapis.com"}},{"@type":"type.googleapis.com/google.rpc.LocalizedMessage","locale":"en-US","message":"API Key not found. Please pass a valid API key."}]}}]
An unexpected critical error occurred:
ApiError: {"error":{"code":400,"message":"API Key not found. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID","domain":"googleapis.com","metadata":{"service":"generativelanguage.googleapis.com"}},{"@type":"type.googleapis.com/google.rpc.LocalizedMessage","locale":"en-US","message":"API Key not found. Please pass a valid API key."}]}}
    at throwErrorIfNotOK (file:///Users/alien/.bun/install/global/node_modules/@google/genai/dist/node/index.mjs:14072:30)
    at process.processTicksAndRejections (node:internal/process/task_queues:105:5)
    at async file:///Users/alien/.bun/install/global/node_modules/@google/genai/dist/node/index.mjs:13848:13
    at async GeminiClient.tryCompressChat (file:///Users/alien/.bun/install/global/node_modules/@google/gemini-cli-core/dist/src/core/client.js:485:53)
    at async GeminiClient.sendMessageStream (file:///Users/alien/.bun/install/global/node_modules/@google/gemini-cli-core/dist/src/core/client.js:339:28)
    at async file:///Users/alien/.bun/install/global/node_modules/@google/gemini-cli/dist/src/nonInteractiveCli.js:53:34
    at async main (file:///Users/alien/.bun/install/global/node_modules/@google/gemini-cli/dist/src/gemini.js:317:5)
