# Repository instructions for coding agents

## Priority and repository-specific rules

- Preserve the existing architecture and working behavior. Prefer small, focused changes, reuse existing services and abstractions, and avoid unrelated refactoring.
- Preserve backward compatibility with Surveillance mode.
- Treat docker-compose.demo.yml as the current working deployment baseline. Treat root docker-compose.yml as a potentially obsolete legacy configuration; do not use it as the architecture baseline without checking with the user.
- Never modify unrelated uncommitted files. Inspect git status before editing and stage only files relevant to the authorized task.
- Never modify production or existing Surveillance data, databases, containers or volumes.
- Never commit or push without explicit user authorization.
- The host reboot during earlier testing had no established cause. Do not attribute it to tests without evidence; nevertheless, control resource-intensive work as specified below.
- Read docs/TEST_INVENTORY.md and relevant progress reports before selecting verification. Historical PASS results are evidence for unchanged code, not proof for code changed afterward.

## Mandatory selective testing policy

### Default: no full regression

Do not run the entire regression suite by default. Select tests from the files, classes, services and behavior changed in the current task. Use the smallest scope that gives meaningful confidence.

Selection order:

1. Inspect changed files and identify affected behavior and dependencies.
2. Find tests that directly cover that behavior.
3. Prefer one exact test method; otherwise use one test class.
4. Expand to closely related tests only when the change justifies it.
5. Run a module suite only when shared behavior changed or narrower verification is insufficient.
6. Obtain explicit user approval before full repository regression, broad integration suites or load/stress/soak tests.

A previous PASS is historical evidence. Rerun a passed test when it directly covers changed behavior and the change makes that result stale. Resume only unfinished work after an interruption; do not repeat failed, interrupted or unknown scopes automatically. Do not run all NOT RUN tests just to increase a PASS count.

### Java / Gradle

**Run each module's filtered test task in a separate Gradle invocation. Never combine multiple Gradle `Test` tasks with `--tests` filters in one invocation.** Gradle may apply filters differently across tasks and execute unrelated suites. After every filtered run, inspect its JUnit XML reports and verify that only the intended classes/methods executed.

Do not automatically rerun previously passed tests after fixing a test fixture. Rerun them only when the fix changes behavior they cover or otherwise makes their result stale.

Prefer filtered Gradle tests and bounded resource use:

~~~bash
./gradlew --no-daemon --no-parallel --max-workers=1 \
  :services:stream-service:test \
  --tests 'com.coltrack.streamservice.service.StreamManagerLeaseTest'
~~~

For one changed method, use an exact class-and-method filter:

~~~bash
./gradlew --no-daemon --no-parallel --max-workers=1 \
  :services:stream-service:test \
  --tests 'com.coltrack.streamservice.service.StreamManagerLeaseTest.concurrentAcquireCallsReuseTheSameActiveSession'
~~~

Do not automatically run ./gradlew test, ./gradlew build or ./gradlew check; these can schedule broad test tasks. Compilation-only tasks may be used when appropriate, but inspect task dependencies first and do not let them execute unrelated tests.

### Rust

The Rust agent is under agents/video-ingest-agent; its nested AGENTS.md also applies and its educational comments must be preserved. That nested file lists cargo check/test/clippy as preferred commands; interpret them as available options, not a requirement to run the full test suite. This root selective policy controls local test scope. Prefer a Cargo test-name filter for changed behavior, for example cargo test --locked <test-name-filter>. Use CARGO_BUILD_JOBS=1 for resource-sensitive work. Do not run the complete Cargo suite unless approved or the changed scope clearly requires it. Existing GitHub Actions runs cargo check --locked, cargo test --locked, Clippy, a stream-service test task and a MediaMTX smoke script when its path filters match; do not assume CI breadth is appropriate for local validation.

### Python inference worker

The repository currently uses Python unittest, not a configured pytest suite. README documents this full command:

~~~bash
PYTHONPATH=workers/inference-worker/src python -m unittest discover -s workers/inference-worker/tests
~~~

For changes, target the affected test file with unittest discovery and a filename pattern; use a single test selector where import/package layout allows it. Do not run all inference tests by default. Do not start inference, GPU workloads, download model weights or datasets for ordinary validation.

### Frontend

frontend/package.json currently defines npm run build and npm run test:broadcast:e2e; it does not define a general unit-test or standalone typecheck script. Prefer the narrowest relevant check available. npm run build includes vue-tsc -b and Vite production bundling, so use it only when needed. Do not install Playwright browsers or run browser E2E for unrelated UI changes; E2E requires explicit approval when it entails a broad/integration run.

### Docker and integration

Use static validation such as docker compose -f docker-compose.demo.yml config -q or docker compose -f docker-compose.streaming.yml config -q when Compose files change. Static config validation does not authorize starting services.

Do not automatically start the entire stack, build every image, or run broad integration suites. Never restart, remove, recreate or otherwise alter existing Surveillance containers or volumes. Integration tests must use an isolated Compose project name, network, volumes and database; verify the exact target resources before any operation. Do not run migrations against production data. Obtain approval before broad integration tests.

### Load and stress

Never run load, stress, soak or high-concurrency tests automatically. They require explicit user approval even when relevant to the code. Approval for unit tests does not authorize load tests.

## Resource and operational safety

- Before expensive builds or tests, inspect available disk and memory. Reuse existing Gradle/Cargo/npm caches where possible; do not place persistent dependency caches under /tmp.
- Default Gradle limits are --no-parallel --max-workers=1. Avoid concurrent Docker builds and simultaneous Gradle, Cargo, inference or Docker workloads.
- Do not delete Docker images, containers, volumes, build caches or dependency caches without explicit authorization. Never use broad destructive cleanup commands.
- Do not automatically download large model weights or test datasets.
- Explain scope and resource impact and obtain approval before potentially resource-intensive operations.
- Do not run Docker Compose up, down, restart, remove or recreate operations against existing deployments.

## Test reporting

After code changes, report:

- Components changed and affected behavior.
- Tests selected and why; tests actually executed with their result.
- PASS, FAIL, SKIPPED and NOT RUN separately; distinguish current results from historical results.
- Tests deliberately not repeated.
- Compilation/static validation results and remaining risks.

Never claim a test passed without an actual result. Keep source test methods, executed test cases/parameterized invocations, and assertions as separate counts. Update relevant test documentation without erasing historical evidence.

## Phase 3 tenant/security work

For tenant-related changes, prioritize authorization and security-boundary tests: tenant ownership, cross-tenant Camera/Stream/Broadcast access, PUBLIC/PRIVATE playback, WebSocket authorization, asynchronous tenant context and Surveillance compatibility. Never run schema migrations against production. Use isolated test databases for persistence/integration tests. Keep tenant identity derived from authenticated membership, not a request-supplied tenant ID.
