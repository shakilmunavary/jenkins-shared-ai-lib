import os, argparse, json
from langchain_community.vectorstores import Chroma
from langchain_openai import AzureOpenAIEmbeddings

parser = argparse.ArgumentParser()
parser.add_argument("--plan")
parser.add_argument("--namespace")
parser.add_argument("--guardrails")
parser.add_argument("--output")
args = parser.parse_args()

with open(args.plan) as f:
    plan_data = json.load(f)

with open(args.guardrails) as f:
    guardrails_text = f.read()

embeddings = AzureOpenAIEmbeddings(
    azure_endpoint=os.getenv("AZURE_API_BASE"),
    api_key=os.getenv("AZURE_API_KEY"),
    model="text-embedding-ada-002",
    api_version="2023-05-15",
    chunk_size=512
)

db = Chroma(collection_name=args.namespace, persist_directory="./chroma", embedding_function=embeddings)
semantic_context = db.similarity_search("Terraform module logic and resource configuration", k=5)

payload = {
    "instructions": (
        "You are a compliance auditor. For each AWS resource in the Terraform plan, "
        "match applicable guardrails and evaluate compliance. Output a table with columns: "
        "Resource Name, Rule ID, Rule, Percentage Met, Details. Then compute and display "
        "**Overall Guardrail Coverage** as a percentage at the end."
    ),
    "terraform_plan": plan_data,
    "guardrails": guardrails_text,
    "semantic_context": [doc.page_content for doc in semantic_context]
}

with open(args.output, "w") as f:
    json.dump(payload, f, indent=2)

print(f"✅ Payload written to {args.output}")
