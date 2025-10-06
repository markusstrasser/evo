(ns temp)

(let [x {[1 2] :a}]
 (get x [1 2]))

(doseq [y (range 8)]
 (println (apply str (for [x (range 8)]
                      (if (even? (+ x y)) "□ " "■ ")))))