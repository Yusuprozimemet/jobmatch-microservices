# Optional modules

Nothing in this folder is required. Week 15 asks for a working pipeline, not for
every tool you have seen. Add a module only when your team has the required
pipeline running and wants to go further.

| Folder | What it adds | Data Track week |
|---|---|---|
| [`llm_classify/`](llm_classify/README.md) | Local LiteLLM notebook — iterate on prompts before dbt | 13 |
| [`python_model/`](python_model/README.md) | `fct_title_discipline` dbt Python model (lives under `dbt/models/marts/`, disabled) | 13 |
| [`streamlit/`](streamlit/README.md) | Pipeline health page (freshness, row counts) | 11 |
| [`dbt_results/`](dbt_results/README.md) | Land dbt run results in `<catalog>.ops.dbt_test_runs` | 10 |

Each subfolder has its own README with setup steps. The dbt LLM model is documented
under `python_model/` but implemented in the dbt project, because dbt only runs Python
from `models/`.
