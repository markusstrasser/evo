(ns utils.session-patch
  "Session/view-state patch merge contract.

   This is intentionally scoped to ephemeral session updates. Do not reuse it
   for node props or kernel DB updates.")

(defn merge-value
  "Merge one session patch value into an existing value.

   Contract:
   - maps recurse
   - sets replace
   - nil clears/replaces
   - vectors replace
   - scalars replace"
  [base patch]
  (if (and (map? base) (map? patch))
    (merge-with merge-value base patch)
    patch))

(defn merge-patch
  "Apply PATCH to SESSION using the session patch contract."
  [session patch]
  (if patch
    (merge-value session patch)
    session))

(defn merge-patches
  "Compose multiple session update fragments."
  [& patches]
  (reduce merge-patch {} patches))
