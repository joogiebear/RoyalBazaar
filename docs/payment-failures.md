# Sale payment failures

A sale reserves the exact inventory quantity before calling Vault. If the provider explicitly rejects the deposit (`transactionSuccess() == false`), RoyalBazaar returns the original stacks, reports an error, and does not advance market price, volume, or successful-trade audit records. Original metadata is retained; items are not regenerated from a configured ID. A rejected sell-all entry is not counted as sold. A later attempt can proceed normally once the provider accepts payments.

Zero/negative quantities are rejected before payment. If the inventory cannot supply the quoted quantity after the pre-trade check, the sale stops before contacting Vault.

## Scope

This does not make Vault and player storage a distributed transaction. A provider that throws after possibly moving money, returns an incorrect success/failure result, or a server crash during settlement has an ambiguous outcome. An exception is propagated, not converted into an explicit rejection; the reserved items are not blindly returned and the payment is not automatically retried. Operators must reconcile the inventory and provider ledger before compensating an ambiguous outcome. Durable crash recovery is separate work.

## Regression coverage

`BazaarServiceTest` drives the real service with a rejecting/accepting economy hook and stateful inventory doubles. It covers full/partial-stack rejection, metadata preservation, success, sell-all retry, invalid quantities, stale inventory, guard rejection, and exception non-retry. These are unit-level fault-injection checks, not a live-player test of every Vault provider.
