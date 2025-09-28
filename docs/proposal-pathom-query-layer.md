# Proposal: A Declarative Query Layer for the Kernel

## 1. Summary

This report proposes the creation of a new, experimental `query` tool that introduces a declarative, EQL-inspired query capability to the kernel. Inspired by the powerful resolver and planner system in **Pathom 3**, this feature will allow users and LLM agents to request nested data structures in a single, declarative query, without needing to manually traverse the tree and reference graph. This will significantly improve the kernel's data access capabilities and provide a foundation for more advanced, automated data fetching.

## 2. Inspiration

This proposal is directly inspired by the core architecture of Pathom 3, a sophisticated graph query library for Clojure.

*   **Source Files**:
    *   `/Users/alien/Projects/inspo-clones/pathom3/src/main/com/wsscode/pathom3/connect/operation.cljc`: This file, particularly the `defresolver` macro, demonstrates how to define declarative units of computation that specify their data inputs and outputs.
    *   `/Users/alien/Projects/inspo-clones/pathom3/src/main/com/wsscode/pathom3/connect/planner.cljc`: This file showcases the power of a query planner that can take a user's request and build a dependency graph of resolvers to fulfill it.

*   **Key Concepts**:
    *   **Declarative Resolvers**: The idea that a piece of the graph (in our case, a node type) can advertise the data it can provide.
    *   **EQL Queries**: Using a declarative, data-based syntax (like EQL) to request nested information.

## 3. Problem: Imperative and Verbose Data Fetching

Currently, retrieving data from the kernel, especially nested or relational data, is an entirely imperative process. The user must:

1.  Know the specific ID of the starting node.
2.  Manually access its `:props`.
3.  Check for children in `:child-ids/by-parent` and recurse.
4.  Check for outgoing references in `:refs`, get the destination ID, and then fetch that node to continue the traversal.

This process is tedious, error-prone, and requires the client to have intimate knowledge of the data's shape and relationships. For an LLM, this is a significant hurdle, requiring multiple, sequential tool calls to gather the necessary context before it can perform a mutation.

## 4. Solution: A Pathom-Inspired Query Tool

I propose a new, non-primitive `query` tool that introduces a simplified, Pathom-like query engine.

### 4.1. Schema-Advertised Data (`:query/provides`)

First, we will enhance our schema (e.g., in a new, centralized node type registry) to allow node types to declare what data they can provide.

```clojure
;; Example node type registration
{:node/type :book
 :query/provides #{:book/title :book/pages :book/author}}
```
Here, the `:book` type advertises that it can directly provide a title, page count, and a reference to an author.

### 4.2. The `query` Tool

The new tool will accept a `db` and an EQL-style query.

**Example Usage:**

```clojure
(query/run db
  {:root ["node-123"]} ;; Starting entity
  [{:book/author [:author/name]} ;; The query
   :book/title])
```

**How it Works (Simplified Planner):**

1.  The `query` tool receives the query and the starting entity ID ("node-123").
2.  It fetches the "node-123" node from the `db`. Let's say its `:type` is `:book`.
3.  It checks the `:query/provides` declaration for the `:book` type.
4.  It sees that `:book/title` is provided, so it retrieves that value from the node's `:props`.
5.  It sees that `:book/author` is also provided. It knows this is a reference, so it looks up the destination ID in `db :refs :book/author "node-123"`.
6.  It gets the author's ID (e.g., "author-456") and recursively runs the sub-query `[:author/name]` starting from that new entity.
7.  It continues this process until the entire query is satisfied, returning a nested map of the results.

**Example Output:**

```clojure
=> {:book/title "The Stand"
    :book/author {:author/name "Stephen King"}}
```

## 5. Benefits

1.  **Declarative Data Fetching:** Users can now ask for *what* they want, not *how* to get it. This dramatically simplifies data access logic.
2.  **Reduced LLM Tool Calls:** An LLM can now gather all the context it needs in a single, declarative `query` call, rather than a long and fragile chain of imperative `read_file` and `get_in` style operations.
3.  **Decoupling:** The client no longer needs to know the specific implementation details of how data is stored or related. It only needs to know the available attributes, which can be discovered via the schema.
4.  **Foundation for Advanced Features:** This is a stepping stone. While this initial implementation is a "manual" planner, it lays the groundwork for a more sophisticated, automatic planner in the future, bringing us closer to the full power of Pathom.
