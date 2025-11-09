import argparse, os
from langchain_community.vectorstores import Chroma
from langchain_community.embeddings import OpenAIEmbeddings

parser = argparse.ArgumentParser()
parser.add_argument("--plan")
parser.add_argument("--namespace")
args = parser.parse_args()

with open(args.plan) as f:
    query = f.read()

openai_key = os.getenv("OPENAI_API_KEY")
db = Chroma(collection_name=args.namespace, persist_directory="./chroma", embedding_function=OpenAIEmbeddings(openai_api_key=openai_key))
results = db.similarity_search(query, k=5)

for doc in results:
    print(doc.page_content)
