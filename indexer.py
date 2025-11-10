import os
import argparse
from langchain.vectorstores import FAISS
from langchain.embeddings import AzureOpenAIEmbeddings
from langchain.document_loaders import TextLoader
from langchain.text_splitter import RecursiveCharacterTextSplitter

parser = argparse.ArgumentParser()
parser.add_argument("--code_dir")
parser.add_argument("--guardrails")
parser.add_argument("--namespace")
args = parser.parse_args()

# ✅ Azure OpenAI Embeddings
embeddings = AzureOpenAIEmbeddings(
    azure_endpoint=os.getenv("AZURE_API_BASE"),
    api_key=os.getenv("AZURE_API_KEY"),
    model="text-embedding-ada-002",
    api_version="2023-05-15",
    chunk_size=512
)

# ✅ Text splitter
splitter = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)

# ✅ Load and split documents
docs = []
for root, _, files in os.walk(args.code_dir):
    for f in files:
        if f.endswith(".tf") or f.endswith(".tf.json"):
            docs += splitter.split_documents(TextLoader(os.path.join(root, f)).load())

docs += splitter.split_documents(TextLoader(args.guardrails).load())

# ✅ Index using FAISS
vectorstore = FAISS.from_documents(docs, embeddings)
vectorstore.save_local(f"./faiss_index/{args.namespace}")
