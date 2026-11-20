package com.example.data.nvidia

import com.example.data.local.NimModelEntity

object NvidiaModelCatalog {

    val curatedModels: List<NimModelEntity> = listOf(
        // === Coding & Agentic Flagships ===
        NimModelEntity(
            id = "qwen/qwen3-coder-480b-a35b-instruct",
            provider = "Qwen",
            displayName = "Qwen3 Coder 480B A35B",
            category = "Coding",
            contextLength = "256K",
            supportsTools = true,
            supportsVision = false,
            description = "Alibaba's flagship MoE coding & agentic engineering model built for repository-level tool use.",
            isNew = true
        ),
        NimModelEntity(
            id = "qwen/qwen3-235b-a22b",
            provider = "Qwen",
            displayName = "Qwen3 235B A22B",
            category = "Reasoning",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Hybrid thinking & fast-response flagship MoE model with strong coding and math reasoning.",
            isNew = true
        ),
        NimModelEntity(
            id = "qwen/qwen2.5-coder-32b-instruct",
            provider = "Qwen",
            displayName = "Qwen2.5 Coder 32B Instruct",
            category = "Coding",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Dedicated high-accuracy 32B code generation, debugging, and refactoring specialist."
        ),
        NimModelEntity(
            id = "qwen/qwq-32b",
            provider = "Qwen",
            displayName = "QwQ 32B Reasoning",
            category = "Reasoning",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Step-by-step reasoning model tailored for complex algorithmic and debugging workflows."
        ),
        NimModelEntity(
            id = "deepseek-ai/deepseek-v4-flash",
            provider = "DeepSeek",
            displayName = "DeepSeek V4 Flash",
            category = "Fast",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "High-throughput MoE model optimized for rapid coding loops and low-latency CLI agents.",
            isNew = true
        ),
        NimModelEntity(
            id = "deepseek-ai/deepseek-v4-pro",
            provider = "DeepSeek",
            displayName = "DeepSeek V4 Pro",
            category = "Flagship",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Frontier DeepSeek MoE model excelling at multi-file software architecture and tool use.",
            isNew = true
        ),
        NimModelEntity(
            id = "deepseek-ai/deepseek-v3.2",
            provider = "DeepSeek",
            displayName = "DeepSeek V3.2",
            category = "Coding",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Sparse-attention frontier model with strong agentic tool-calling and Python proficiency.",
            isNew = true
        ),
        NimModelEntity(
            id = "deepseek-ai/deepseek-r1",
            provider = "DeepSeek",
            displayName = "DeepSeek R1",
            category = "Reasoning",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Full chain-of-thought reasoning flagship for deep root-cause analysis and math."
        ),
        NimModelEntity(
            id = "deepseek-ai/deepseek-r1-distill-llama-70b",
            provider = "DeepSeek",
            displayName = "DeepSeek R1 Distill Llama 70B",
            category = "Reasoning",
            contextLength = "128K",
            supportsTools = false,
            supportsVision = false,
            description = "Distilled reasoning checkpoint combining DeepSeek-R1 thought traces with Llama 3.3 speed."
        ),
        NimModelEntity(
            id = "deepseek-ai/deepseek-r1-distill-qwen-32b",
            provider = "DeepSeek",
            displayName = "DeepSeek R1 Distill Qwen 32B",
            category = "Reasoning",
            contextLength = "128K",
            supportsTools = false,
            supportsVision = false,
            description = "Compact reasoning model strong in Python logic and competitive programming."
        ),
        NimModelEntity(
            id = "deepseek-ai/deepseek-r1-distill-qwen-14b",
            provider = "DeepSeek",
            displayName = "DeepSeek R1 Distill Qwen 14B",
            category = "Fast",
            contextLength = "64K",
            supportsTools = false,
            supportsVision = false,
            description = "Lightweight reasoning model for snappy mobile iterations."
        ),
        // === Meta Llama Family ===
        NimModelEntity(
            id = "meta/llama-4-maverick-17b-128e-instruct",
            provider = "Meta",
            displayName = "Llama 4 Maverick 17B (128E)",
            category = "Flagship",
            contextLength = "1M",
            supportsTools = true,
            supportsVision = true,
            description = "Meta's 128-expert multimodal MoE flagship with massive context and native tool calling.",
            isNew = true
        ),
        NimModelEntity(
            id = "meta/llama-4-scout-17b-16e-instruct",
            provider = "Meta",
            displayName = "Llama 4 Scout 17B (16E)",
            category = "Fast",
            contextLength = "10M",
            supportsTools = true,
            supportsVision = true,
            description = "Ultra-long context 16-expert MoE model for scanning large codebases rapidly.",
            isNew = true
        ),
        NimModelEntity(
            id = "meta/llama-3.3-70b-instruct",
            provider = "Meta",
            displayName = "Llama 3.3 70B Instruct",
            category = "Coding",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Reliable workhorse 70B model with rock-solid OpenAI-compatible function calling."
        ),
        NimModelEntity(
            id = "meta/llama-3.1-405b-instruct",
            provider = "Meta",
            displayName = "Llama 3.1 405B Instruct",
            category = "Flagship",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Dense 405B general intelligence model hosted on NVIDIA NIM."
        ),
        NimModelEntity(
            id = "meta/llama-3.1-70b-instruct",
            provider = "Meta",
            displayName = "Llama 3.1 70B Instruct",
            category = "Flagship",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Balanced 70B model for general scripting and analysis."
        ),
        NimModelEntity(
            id = "meta/llama-3.1-8b-instruct",
            provider = "Meta",
            displayName = "Llama 3.1 8B Instruct",
            category = "Fast",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Ultra-fast 8B model for quick syntax checks and lightweight tasks."
        ),
        NimModelEntity(
            id = "meta/llama-3.2-90b-vision-instruct",
            provider = "Meta",
            displayName = "Llama 3.2 90B Vision",
            category = "Vision",
            contextLength = "128K",
            supportsTools = false,
            supportsVision = true,
            description = "Multimodal Llama 3.2 model capable of image and diagram reasoning."
        ),
        NimModelEntity(
            id = "meta/llama-3.2-3b-instruct",
            provider = "Meta",
            displayName = "Llama 3.2 3B Instruct",
            category = "Fast",
            contextLength = "128K",
            supportsTools = false,
            supportsVision = false,
            description = "Edge-sized ultra-low-latency model."
        ),
        // === NVIDIA Nemotron Family ===
        NimModelEntity(
            id = "nvidia/nemotron-3-super",
            provider = "NVIDIA",
            displayName = "Nemotron 3 Super",
            category = "Flagship",
            contextLength = "256K",
            supportsTools = true,
            supportsVision = false,
            description = "NVIDIA's flagship hybrid Mamba-Transformer model tuned for agentic coding and tool use.",
            isNew = true
        ),
        NimModelEntity(
            id = "nvidia/nemotron-3.5-super-vl-120b",
            provider = "NVIDIA",
            displayName = "Nemotron 3.5 Super VL 120B",
            category = "Vision",
            contextLength = "256K",
            supportsTools = true,
            supportsVision = true,
            description = "NVIDIA's next-gen 120B vision-language flagship with deep reasoning and agent tools.",
            isNew = true
        ),
        NimModelEntity(
            id = "nvidia/llama-3.1-nemotron-ultra-253b-v1",
            provider = "NVIDIA",
            displayName = "Nemotron Ultra 253B",
            category = "Reasoning",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "253B scientific & coding reasoning powerhouse with toggleable thinking mode.",
            isNew = true
        ),
        NimModelEntity(
            id = "nvidia/llama-3.3-nemotron-super-49b-v1",
            provider = "NVIDIA",
            displayName = "Nemotron Super 49B v1",
            category = "Reasoning",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "High-efficiency 49B reasoning and tool-use model optimized on single-node H100s."
        ),
        NimModelEntity(
            id = "nvidia/llama-3.1-nemotron-70b-instruct",
            provider = "NVIDIA",
            displayName = "Llama 3.1 Nemotron 70B",
            category = "Flagship",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "RLHF-tuned 70B model built for helpfulness and instruction accuracy."
        ),
        NimModelEntity(
            id = "nvidia/llama-3.1-nemotron-nano-8b-v1",
            provider = "NVIDIA",
            displayName = "Nemotron Nano 8B v1",
            category = "Fast",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Compact reasoning-capable 8B model for rapid agent turns."
        ),
        // === Mistral & Codestral / Devstral ===
        NimModelEntity(
            id = "mistralai/devstral-2-123b-instruct-2512",
            provider = "Mistral AI",
            displayName = "Devstral 2 123B",
            category = "Coding",
            contextLength = "256K",
            supportsTools = true,
            supportsVision = false,
            description = "Mistral's specialized software engineering agent model built for multi-file CLI workflows.",
            isNew = true
        ),
        NimModelEntity(
            id = "mistralai/codestral-22b-instruct-v0.1",
            provider = "Mistral AI",
            displayName = "Codestral 22B",
            category = "Coding",
            contextLength = "32K",
            supportsTools = false,
            supportsVision = false,
            description = "Low-latency code completion and Python/shell generation specialist."
        ),
        NimModelEntity(
            id = "mistralai/mistral-nemotron",
            provider = "Mistral AI",
            displayName = "Mistral Nemotron",
            category = "Coding",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Co-developed by Mistral and NVIDIA specifically for agentic function calling.",
            isNew = true
        ),
        NimModelEntity(
            id = "mistralai/mistral-large-2-instruct",
            provider = "Mistral AI",
            displayName = "Mistral Large 2",
            category = "Flagship",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "123B multilingual & coding flagship with native tool calling."
        ),
        NimModelEntity(
            id = "mistralai/mistral-small-3.1-24b-instruct-2503",
            provider = "Mistral AI",
            displayName = "Mistral Small 3.1 24B",
            category = "Fast",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = true,
            description = "Fast 24B multimodal model with strong function calling."
        ),
        // === Zhipu GLM, Moonshot Kimi, OpenAI GPT-OSS, Google Gemma, Microsoft Phi ===
        NimModelEntity(
            id = "z-ai/glm-5.3",
            provider = "Z-AI (GLM)",
            displayName = "GLM 5.3",
            category = "Coding",
            contextLength = "200K",
            supportsTools = true,
            supportsVision = false,
            description = "Latest Zhipu GLM frontier agentic coding model hosted on NVIDIA NIM.",
            isNew = true
        ),
        NimModelEntity(
            id = "z-ai/glm-5.3-flash",
            provider = "Z-AI (GLM)",
            displayName = "GLM 5.3 Flash",
            category = "Fast",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "High-speed GLM variant for responsive terminal coding loops.",
            isNew = true
        ),
        NimModelEntity(
            id = "moonshotai/kimi-k2.6",
            provider = "Moonshot AI",
            displayName = "Kimi K2.6",
            category = "Coding",
            contextLength = "256K",
            supportsTools = true,
            supportsVision = false,
            description = "Moonshot's trillion-scale MoE agentic coding model with strong CLI tool orchestration.",
            isNew = true
        ),
        NimModelEntity(
            id = "moonshotai/kimi-k2-instruct",
            provider = "Moonshot AI",
            displayName = "Kimi K2 Instruct",
            category = "Coding",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Agentic MoE coding model built for autonomous tool execution."
        ),
        NimModelEntity(
            id = "openai/gpt-oss-120b",
            provider = "OpenAI OSS",
            displayName = "GPT-OSS 120B",
            category = "Reasoning",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "OpenAI's open-weight 120B reasoning & tool-use model running on NVIDIA NIM.",
            isNew = true
        ),
        NimModelEntity(
            id = "openai/gpt-oss-20b",
            provider = "OpenAI OSS",
            displayName = "GPT-OSS 20B",
            category = "Fast",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Fast open-weight 20B model with adjustable reasoning effort and tool calls.",
            isNew = true
        ),
        NimModelEntity(
            id = "google/gemma-3-27b-it",
            provider = "Google",
            displayName = "Gemma 3 27B IT",
            category = "Vision",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = true,
            description = "Google's open-weight 27B multimodal model with 128K context."
        ),
        NimModelEntity(
            id = "google/gemma-2-27b-it",
            provider = "Google",
            displayName = "Gemma 2 27B IT",
            category = "Fast",
            contextLength = "8K",
            supportsTools = false,
            supportsVision = false,
            description = "Compact, high-efficiency 27B instruction-tuned model."
        ),
        NimModelEntity(
            id = "microsoft/phi-4",
            provider = "Microsoft",
            displayName = "Phi-4 14B",
            category = "Reasoning",
            contextLength = "16K",
            supportsTools = false,
            supportsVision = false,
            description = "Compact 14B STEM and code reasoning specialist."
        ),
        NimModelEntity(
            id = "ibm/granite-3.3-8b-instruct",
            provider = "IBM",
            displayName = "Granite 3.3 8B Instruct",
            category = "Fast",
            contextLength = "128K",
            supportsTools = true,
            supportsVision = false,
            description = "Enterprise-tuned 8B instruction & tool-calling model."
        )
    )

    fun fromLiveApiId(rawId: String, existingMap: Map<String, NimModelEntity>, syncTimestamp: Long): NimModelEntity {
        val existing = existingMap[rawId]
        if (existing != null) {
            return existing.copy(isLiveApi = true, lastSyncedAt = syncTimestamp)
        }

        val parts = rawId.split("/", limit = 2)
        val rawProvider = if (parts.size == 2) parts[0] else "NVIDIA NIM"
        val rawModel = if (parts.size == 2) parts[1] else rawId

        val provider = when (rawProvider.lowercase()) {
            "meta" -> "Meta"
            "deepseek-ai" -> "DeepSeek"
            "qwen" -> "Qwen"
            "nvidia" -> "NVIDIA"
            "mistralai" -> "Mistral AI"
            "google" -> "Google"
            "microsoft" -> "Microsoft"
            "z-ai" -> "Z-AI (GLM)"
            "moonshotai" -> "Moonshot AI"
            "openai" -> "OpenAI OSS"
            "ibm" -> "IBM"
            "minimaxai" -> "MiniMax"
            "stepfun-ai" -> "StepFun"
            else -> rawProvider.replaceFirstChar { it.uppercase() }
        }

        val lower = rawId.lowercase()
        val isEmbedOrGuard = lower.contains("embed") || lower.contains("rerank") ||
            lower.contains("guard") || lower.contains("safety") || lower.contains("parse") ||
            lower.contains("ocr") || lower.contains("whisper")
        val category = when {
            isEmbedOrGuard -> "Utility"
            lower.contains("coder") || lower.contains("codestral") || lower.contains("devstral") -> "Coding"
            lower.contains("vl") || lower.contains("vision") -> "Vision"
            lower.contains("r1") || lower.contains("qwq") || lower.contains("thinking") || lower.contains("reason") -> "Reasoning"
            lower.contains("flash") || lower.contains("mini") || lower.contains("nano") || lower.contains("8b") || lower.contains("7b") || lower.contains("3b") || lower.contains("1b") -> "Fast"
            else -> "Flagship"
        }

        val displayName = rawModel
            .split("-")
            .joinToString(" ") { token ->
                when {
                    token.matches(Regex("\\d+[bmBM]?")) -> token.uppercase()
                    token == "vl" || token == "it" || token == "oss" -> token.uppercase()
                    else -> token.replaceFirstChar { it.uppercase() }
                }
            }

        return NimModelEntity(
            id = rawId,
            provider = provider,
            displayName = displayName,
            category = category,
            contextLength = if (lower.contains("scout")) "10M" else "128K",
            supportsTools = !isEmbedOrGuard && !lower.contains("distill"),
            supportsVision = lower.contains("vision") || lower.contains("-vl"),
            description = "Synced directly from NVIDIA NIM live catalog ($rawId).",
            isLiveApi = true,
            isFavorite = false,
            isNew = true,
            lastSyncedAt = syncTimestamp
        )
    }
}
