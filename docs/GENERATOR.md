# Why the generator is `tools/generate.py`

The first plan for this SDK was Kiota, with a hand-written layer for unknown union variants. This SDK generates plain
models instead. The reasons, measured with Kiota 1.37.0, which passed 14 of the 17 acceptance scenarios:

| Need | Kiota Java | `tools/generate.py` |
|---|---|---|
| S12, unknown union variants | Parses to an empty wrapper, every member lost, and serializing it throws `IllegalStateException` (3 of the 3 misses). A hand-written layer would have to re-parse each union field beside the model | Decodes to the union's `Unknown`, every member kept in `raw()` |
| S12, unknown enum values | Only with the overlay that turns every open enum into a plain `String`, which loses the typed values | `OpenEnum`: typed constants, and unknown values kept |
| S12, requests | Defaults leak into the body: `CreateTranscriptVersionRequest` sends `trigger: manual`, `MergeSegmentsRequest` sends `retranslate: true` unasked | Only what the caller set |
| Naming | Path-based request builders (`projects().byProjectId(id).segments()...`), so a second hand-written layer would have to map 82 operationIds | Methods named after the operationId |
| S3, S4, S7, S10, S11 | All hand-written around its middleware anyway | Hand-written, on `java.net.http` |
| Runtime dependencies | Kiota's runtime libraries, with OkHttp and Gson, in the customer's classpath | None |

The whole generator is one Python file over the contract, with no overlay: it reads `x-rask-open-enum` and
`x-rask-open-union` itself, and the only SDK-12 action it needs (`Idempotency-Key` optional in signatures) is how every
signature here works anyway. What it gives up: a generator we did not write would pick up new OpenAPI keywords by itself.
This one exits on a schema shape it does not know, so a contract change that needs it fails loudly.
