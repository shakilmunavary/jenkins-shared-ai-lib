import os, argparse
from langchain_community.vectorstores import Chroma
from langchain_openai import AzureOpenAIEmbeddings
from langchain_community.document_loaders import TextLoader
from langchain.text_splitter import RecursiveCharacterTextSplitter

parser = argparse.ArgumentParser()
parser.add_argument("--code_dir")
parser.add_argument("--guardrails")
parser.add_argument("--namespace")
args = parser.parse_args()

embeddings = AzureOpenAIEmbeddings(
    azure_endpoint=os.getenv("AZURE_API_BASE"),
    api_key=os.getenv("AZURE_API_KEY"),
    deployment_name=os.getenv("DEPLOYMENT_NAME"),
    model="text-embedding-ada-002",  # Replace with your actual model name if different
    api_version=os.getenv("AZURE_API_VERSION"),
    chunk_size=512
)

splitter = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)

docs = []
for root, _, files in os.walk(args.code_dir):
    for f in files:
        if f.endswith(".tf") or f.endswith(".tf.json"):
            docs += splitter.split_documents(TextLoader(os.path.join(root, f)).load())

docs += splitter.split_documents(TextLoader(args.guardrails).load())

Chroma.from_documents(docs, embeddings, collection_name=args.namespace, persist_directory="./chroma").persist()
