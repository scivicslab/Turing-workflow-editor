[![Javadoc](https://img.shields.io/badge/javadoc-1.0.0-brightgreen.svg)](https://scivicslab.github.io/Turing-workflow-editor/apidocs/)
[![Sponsor](https://img.shields.io/github/sponsors/scivicslab)](https://github.com/sponsors/scivicslab)

# Turing Workflow Editor

**Official Website: [scivicslab.com](https://scivicslab.com)**

A web UI for editing and executing [POJO-actor](https://github.com/scivicslab/POJO-actor) YAML workflows. Built on Quarkus with [turing-workflow](https://github.com/scivicslab/turing-workflow) engine.

## Features

- **Nested step-group editor** — Each step shows `from → to` states, `label`, `note`, and a sub-table of actions (actor, method, arguments)
- **Multi-tab workflows** — Work on multiple workflows simultaneously with add/rename/delete tabs
- **Workflow description** — Editable description field for the workflow
- **Real-time execution log** — Live output via SSE with configurable log levels (ALL / INFO / OFF)
- **Run / Stop / Resume** — Full execution control including pause/resume support
- **Visual step highlighting** — Active step is highlighted during workflow execution
- **Built-in actors** — `shell` (command execution), `log` (logging), `loader` (dynamic JAR loading)
- **Dynamic actor loading** — Load actors from JARs at runtime via `DynamicActorLoaderActor`
- **MCP Server** — Exposes workflow operations as MCP tools via `quarkus-mcp-server-http`
- **REST API** — Full CRUD, YAML import/export, run/stop/resume
- **YAML import/export** — Server-side SnakeYAML parsing with full support for `description`, `label`, `note`
- **localStorage persistence** — Browser state (tabs, theme, settings) survives across server restarts
- **10 themes** — 5 dark (Catppuccin, Nord, Blue, Green, Red) + 5 light (Clean, Warm, Blue, Green, Red)

## Build & Run

```bash
cd Turing-workflow-editor
rm -rf target
mvn install
java -jar target/quarkus-app/quarkus-run.jar
```

Or download a native image binary from [Releases](https://github.com/scivicslab/Turing-workflow-editor/releases):

```bash
./turing-workflow-editor-v1.0.0-linux-x86_64
```

Starts on port 8091 by default (configurable in `application.properties`).

## REST API

### Workflow Operations

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/workflow` | Get current workflow |
| `PUT` | `/api/workflow` | Replace entire workflow |
| `POST` | `/api/workflow/steps?index=N` | Add a step (`index` omitted = append) |
| `PUT` | `/api/workflow/steps/{index}` | Update a step |
| `DELETE` | `/api/workflow/steps/{index}` | Delete a step |

### Tabs

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/tabs` | List all tabs |
| `POST` | `/api/tabs` | Create a new tab |
| `DELETE` | `/api/tabs/{name}` | Delete a tab |
| `PUT` | `/api/tabs/{name}/activate` | Switch to a tab |
| `PUT` | `/api/tabs/{name}/rename` | Rename a tab |

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
| `GET` | `/api/yaml/export` | Export as YAML |
| `POST` | `/api/yaml/import` | Import YAML (`Content-Type: text/plain`) |

### Execution Control

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/run` | Run with UI-format request |
| `POST` | `/api/run/yaml` | Run YAML directly |
| `POST` | `/api/stop` | Stop running workflow |
| `POST` | `/api/resume` | Resume paused workflow |
| `GET` | `/api/status` | Get execution status |
| `GET` | `/api/events` | SSE stream for live log output |

### Dynamic Actor Loading

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/loader/load-jar` | Load a JAR (`{"path": "..."}`) |
| `POST` | `/api/loader/create-child` | Create an actor (`{"name": "...", "className": "..."}`) |
| `GET` | `/api/actors` | List registered actors with available actions |

## Example: Run `llm-hello.yaml` via API

```bash
# 1. Import YAML (reflected in browser)
curl -X POST http://localhost:8091/api/yaml/import \
  -H 'Content-Type: text/plain' \
  -d @examples/llm-hello.yaml

# 2. Run
curl -X POST http://localhost:8091/api/run/yaml \
  -H 'Content-Type: text/plain' \
  -d @examples/llm-hello.yaml
```

## Dependencies

- [POJO-actor](https://github.com/scivicslab/POJO-actor) — Actor framework
- [turing-workflow](https://github.com/scivicslab/turing-workflow) — Workflow execution engine
- Quarkus 3.28 — Web framework
- SnakeYAML — YAML parser
- quarkus-mcp-server-http — MCP server integration

## License

[Apache License 2.0](LICENSE)
