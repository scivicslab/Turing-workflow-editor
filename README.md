# POJO-actor Workflow Editor

A Quarkus web UI for editing and executing [POJO-actor](https://github.com/scivicslab/POJO-actor) YAML workflows.

## Features

- **Nested step-group editor** — Each step shows `from → to` states, `label`, `note`, and a sub-table of actions (actor, method, arguments)
- **Workflow description** — Editable description field for the workflow
- **Real-time execution log** — Instant output via SSE (Server-Sent Events) with configurable log levels (ALL / INFO / OFF)
- **Stop control** — Stop running workflows via `Interpreter.requestStop()`
- **Built-in actors** — `shell` (command execution), `log` (logging), `loader` (dynamic JAR loading)
- **REST API** — External workflow manipulation (CRUD, YAML import/export, run/stop)
- **Dynamic actor loading** — Load actors from JARs at runtime via `DynamicActorLoaderActor`
- **localStorage persistence** — Browser state survives across server restarts and page reloads
- **YAML import/export** — Server-side SnakeYAML parsing with full support for `description`, `label`, `note`
- **10 themes** — 5 dark + 5 light, persisted in `localStorage`

## Build & Run

```bash
cd quarkus-workflow-editor
rm -rf target
mvn install
java -jar target/quarkus-app/quarkus-run.jar
```

Starts on port 8091 by default (configurable in `application.properties`).

## REST API

### Workflow Operations

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/workflow` | Get current workflow (returns both `steps` and `rows`) |
| `PUT` | `/api/workflow` | Replace entire workflow (accepts `steps` or `rows`) |
| `POST` | `/api/workflow/steps?index=N` | Add a step (`index` omitted = append) |
| `PUT` | `/api/workflow/steps/{index}` | Update a step |
| `DELETE` | `/api/workflow/steps/{index}` | Delete a step |

### Transition / Sub-action Operations

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/workflow/transitions` | List transitions (including actions) |
| `POST` | `/api/workflow/transitions/{t}/actions` | Add a sub-action |
| `PUT` | `/api/workflow/transitions/{t}/actions/{a}` | Update a sub-action |
| `DELETE` | `/api/workflow/transitions/{t}/actions/{a}` | Delete a sub-action |

### YAML

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/yaml/export` | Export as YAML (with `description`, `label`, `note`) |
| `POST` | `/api/yaml/import` | Import YAML (`Content-Type: text/plain`) |

### Execution Control

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/run` | Run with UI-format request |
| `POST` | `/api/run/yaml` | Run YAML directly |
| `POST` | `/api/stop` | Stop running workflow |
| `GET` | `/api/status` | Get execution status |

### Dynamic Actor Loading

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/loader/load-jar` | Load a JAR (`{"path": "..."}`) |
| `POST` | `/api/loader/create-child` | Create an actor (`{"name": "...", "className": "..."}`) |
| `GET` | `/api/actors` | List registered actors |

## Example: Run `turing87.yaml` via API

```bash
# 1. Load JAR
curl -X POST http://localhost:8091/api/loader/load-jar \
  -H 'Content-Type: application/json' \
  -d '{"path": "/path/to/actor-WF-examples.jar"}'

# 2. Create actor
curl -X POST http://localhost:8091/api/loader/create-child \
  -H 'Content-Type: application/json' \
  -d '{"name": "turing", "className": "com.scivicslab.turing.TuringActionIIAR"}'

# 3. Import YAML (reflected in browser)
curl -X POST http://localhost:8091/api/yaml/import \
  -H 'Content-Type: text/plain' \
  -d @turing87.yaml

# 4. Run
curl -X POST http://localhost:8091/api/run/yaml \
  -H 'Content-Type: text/plain' \
  -d @turing87.yaml
```

## Dependencies

- [POJO-actor](https://github.com/scivicslab/POJO-actor) — Workflow execution engine
- Quarkus 3.x — Web framework
- SnakeYAML — YAML parser

## License

[Apache License 2.0](LICENSE)
