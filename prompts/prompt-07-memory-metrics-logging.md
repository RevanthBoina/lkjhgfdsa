# Prompt 07: Memory, Room Persistence, Metrics, & Logging

## Objective
Implement local persistent memory and metrics tracking using Room Database.

## Entities & DAOs:
1. `SessionScoreEntity` & `SessionScoreDao`:
   - Records every executed task with duration, token count, provider decision reason, and final outcome.
2. `AppKnowledgeBaseEntity` & `AppKnowledgeBaseDao`:
   - Stores validated macro trajectory steps per (package, taskSignature) for FastPath replay.
3. `TipEntity` & `TipDao`:
   - Stores learned app-specific operational tips from Reflection episodes.
4. `LogEventEntity` & `LogEventDao`:
   - Structured audit logging of events and watchdog triggers.
5. `MetricsCollector`:
   - In-memory and persisted KPI aggregator (FastPath hit rate, average step latency, LLM token reduction).
