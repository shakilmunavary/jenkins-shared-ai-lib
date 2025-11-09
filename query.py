import argparse
from langchain.vectorstores import Chroma
from langchain.embeddings import OpenAIEmbeddings

parser = argparse.ArgumentParser()
parser.add_argument("--plan")
parser.add_argument("--namespace")
args = parser.parse_args()

with open(args.plan) as f:
    query = f.read()

db = Chroma(collection_name=args.namespace, persist_directory="./chroma", embedding_function=OpenAIEmbeddings())
results = db.similarity_search(query, k=5)

for doc in results:
    print(doc.page_content)
