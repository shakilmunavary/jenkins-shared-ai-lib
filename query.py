import argparse, os
from langchain_community.vectorstores import Chroma
from langchain_openai import AzureOpenAIEmbeddings

parser = argparse.ArgumentParser()
parser.add_argument("--plan")
parser.add_argument("--namespace")
args = parser.parse_args()

with open(args.plan) as f:
    query = f.read()

embeddings = AzureOpenAIEmbeddings(
    azure_endpoint=os.getenv("AZURE_API_BASE"),
    api_key=os.getenv("AZURE_API_KEY"),
    model="text-embedding-ada-002",
    api_version=os.getenv("AZURE_API_VERSION"),
    model_kwargs={"deployment_name": os.getenv("DEPLOYMENT_NAME")},
    chunk_size=2042
)


db = Chroma(collection_name=args.namespace, persist_directory="./chroma", embedding_function=embeddings)
results = db.similarity_search(query, k=5)

for doc in results:
    print(doc.page_content)
