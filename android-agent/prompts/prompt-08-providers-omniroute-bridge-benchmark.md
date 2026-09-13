# Prompt 08: Providers, Omniroute Cloud, Local SLM, Bridge & Benchmark

## Objective
Implement multi-provider client integration with primary focus on Omniroute Cloud API, on-device SLM adapter, and Bridge Server.

## Key Components:
1. `AniobOmniRouteProvider`:
   - Endpoint: `https://api.omniroute.ai/v1/chat/completions`
   - Authorization: `Bearer $OMNIROUTE_API_KEY`
   - Model: `gpt-4o` with multimodal image/jpeg data URL.
   - Structured JSON output enforcement with temperature 0.1.
2. `AniobOpenAICompatProvider`:
   - Unified adapter compatible with DroidRun provider architecture.
3. `AniobOnDeviceProvider`:
   - Local Ollama/llama.cpp HTTP client.
4. `AniobBridgeServer`:
   - Embedded lightweight HTTP/WebSocket bridge allowing host PC / web emulator to inspect screen states, issue NL tasks, and run automated evaluations.
5. `AniobBenchmarkSuite`:
   - Evaluator comparing Task Success Rate, Time-to-Complete, Token Cost, and FastPath Speedup.
