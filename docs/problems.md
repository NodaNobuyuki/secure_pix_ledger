# Problem types

Errors are returned as [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problems
(`application/problem+json`). The `type` member of each problem below links to its section here.
Clients should match on the full `type` URI; `title` and `detail` are for humans and may change.
Rationale: [ADR-0011](adr/0011-http-error-contract.md).

Generic errors (`400` validation, `404` unknown transfer or route, `500`) use `about:blank`, whose
meaning is the HTTP status itself. Validation problems add an `errors` array of
`{"field", "message"}` objects.

## account-not-found

`404`. An account in the path or in the request body does not exist. Internal accounts are
reported the same way. Nothing is stored and the `Idempotency-Key` is **not** consumed, so the
request can be corrected and resent with the same key.

## transfer-rejected

`422`. The transfer was valid but refused by a business rule. It **was stored** with status
`REJECTED` and no money moved. The key is consumed: retrying with the same key replays this exact
response; trying again needs a new key.

Extra members:

| Member | Meaning |
|---|---|
| `reason` | `INSUFFICIENT_FUNDS`, `SOURCE_INACTIVE` or `DESTINATION_INACTIVE` |
| `transferId` | id of the stored transfer; also in `instance` as `/transfers/{id}` |

## idempotency-key-reused

`422`. The `Idempotency-Key` was already used for a request with a different body (source,
destination, amount or currency). Nothing was executed. Use a new key for a new request.

## invalid-transfer

`422`. The request is well-formed but cannot be a transfer, for example source and destination
are the same account. Nothing is stored and the key is not consumed.

## account-busy

`503`, with a `Retry-After` header in seconds. One of the accounts stayed locked by another
transfer for too long. Nothing was executed; retry after the delay **with the same
`Idempotency-Key`**, which guarantees the transfer happens at most once.
