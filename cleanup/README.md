# JANUS cleanup job

The executable cleanup implementation lives in the Java module and is packaged
as `janus-dcr-provider-*-cleanup.jar`. `janus/cleanup.sh` launches it in the
same image used by Keycloak; `infra/modules/cleanup-job.bicep` schedules it as a
single-replica Azure Container Apps Job.

Cleanup is dry-run by default and deletes only after positive ownership,
creation-time, and fresh trusted-use evidence checks plus a just-in-time Graph
re-fetch. Without a reviewed activity observer it retains all applications;
it never substitutes naming or age for evidence.

Configuration and the exact decision algorithm are documented in
[`docs/lifecycle.md`](../docs/lifecycle.md). Start an ad-hoc deployed run with:

```bash
az containerapp job start --name <job-name> --resource-group <resource-group>
```

Review structured decisions in Log Analytics before ever setting
`JANUS_CLEANUP_DRY_RUN=false`.
