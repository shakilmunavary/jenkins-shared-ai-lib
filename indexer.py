import os, argparse
from langchain_community.vectorstores import Chroma
from langchain_community.embeddings import OpenAIEmbeddings
from langchain_community.document_loaders import TextLoader
from langchain.text_splitter import RecursiveCharacterTextSplitter

parser = argparse.ArgumentParser()
parser.add_argument("--code_dir")
parser.add_argument("--guardrails")
parser.add_argument("--namespace")
args = parser.parse_args()

openai_key = os.getenv("OPENAI_API_KEY")
embeddings = OpenAIEmbeddings(openai_api_key=openai_key)
splitter = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)

docs = []
for root, _, files in os.walk(args.code_dir):
    for f in files:
        if f.endswith(".tf") or f.endswith(".tf.json"):
            docs += splitter.split_documents(TextLoader(os.path.join(root, f)).load())

docs += splitter.split_documents(TextLoader(args.guardrails).load())

Chroma.from_documents(docs, embeddings, collection_name=args.namespace, persist_directory="./chroma").persist()
