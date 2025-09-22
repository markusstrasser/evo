(ns kernel)

{:nodes
 {"root" {:id "root", :type :div, :text "Root Container"}
  "p1"   {:id "p1", :type :p, :text "Paragraph 1"}}
 :children {"root" ["p1"]}
 :parents  {"p1" "root"}}


