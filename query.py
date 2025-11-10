import os
import json
import argparse
from langchain.vectorstores import FAISS
from langchain.embeddings import AzureOpenAIEmbeddings
from langchain.document_loaders import TextLoader
from langchain.text_splitter import RecursiveCharacterTextSplitter

parser = argparse.ArgumentParser()
parser.add_argument("--plan")
parser.add_argument("--guardrails")
parser.add_argument("--namespace")
parser.add_argument("--output")
args = parser.parse_args()

# ✅ Load Azure OpenAI Embeddings
embeddings = AzureOpenAIEmbeddings(
    azure_endpoint=os.getenv("AZURE_API_BASE"),
    api_key=os.getenv("AZURE_API_KEY"),
    model="text-embedding-ada-002",
    api_version="2023-05-15",
    chunk_size=512
)

# ✅ Load FAISS index
vectorstore = FAISS.load_local(f"./faiss_index/{args.namespace}", embeddings)

# ✅ Load and split tfplan
splitter = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)
plan_docs = splitter.split_documents(TextLoader(args.plan).load())

# ✅ Load guardrails
guardrail_text = TextLoader(args.guardrails).load()[0].page_content

# ✅ Construct prompt using top-k semantic matches
query_text = plan_docs[0].page_content
matches = vectorstore.similarity_search(query_text, k=5)

context = "\n\n".join([doc.page_content for doc in matches])

prompt = f"""
You are an AI compliance auditor. Use the following guardrails and context to assess the Terraform plan.

Guardrails:
{guardrail_text}

Context from semantic index:
{context}

Terraform Plan:
{query_text}

Respond with a stakeholder-ready summary including overall guardrail coverage, key risks, and recommendations.
"""

# ✅ Build Azure OpenAI payload
payload = {
    "messages": [
        {"role": "system", "content": "You are a Terraform compliance auditor."},
        {"role": "user", "content": prompt}
    ],
    "temperature": 0.2,
    "max_tokens": 1000
}

# ✅ Write payload to file
with open(args.output, "w") as f:
    json.dump(payload, f, indent=2)
