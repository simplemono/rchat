(ns rchat.markdown
  "The model's text as hiccup. nextjournal/markdown parses it, this
  namespace renders the tree: paragraphs, emphasis, inline code, code
  blocks, links, headings, lists, quotes, tables, line breaks. Raw HTML in
  the text stays text, so the model can never put markup on the page, and
  an image becomes a link to it, so the page never loads a URL the model
  chose. The result is plain data for a frame.

  `:leaf` is a function from a text leaf to a seq of children, for an app
  that turns parts of the text into something of its own (positions of a
  cut into jumps, say) inside the markdown."
  (:require [clojure.string :as str]
            [nextjournal.markdown :as md]))

(declare node)

(defn- merge-text
  "Adjacent strings as one, so a soft break or a leaf split leaves no seam."
  [children]
  (reduce (fn [acc child]
            (if (and (string? child) (string? (peek acc)))
              (conj (pop acc) (str (peek acc) child))
              (conj acc child)))
          []
          children))

(defn- nodes
  [opts content]
  (merge-text (mapcat #(node opts %) content)))

(defn- plain
  "The text of a subtree."
  [content]
  (apply str (map (fn [{:keys [text content]}]
                    (or text (plain content)))
                  content)))

(defn- element
  [tag opts content]
  [(into [tag] (nodes opts content))])

(defn- node
  [{:keys [leaf] :as opts} {:keys [type content text attrs heading-level]}]
  (case type
    :text (leaf (str text))
    :softbreak [" "]
    :hardbreak [[:br]]
    :strong (element :strong opts content)
    :em (element :em opts content)
    :strikethrough (element :s opts content)
    :monospace [[:code (plain content)]]
    :link [(into [:a {:href (str (:href attrs))
                      :target "_blank"
                      :rel "noopener"}]
                 (nodes opts content))]
    :image [[:a {:href (str (:src attrs))
                 :target "_blank"
                 :rel "noopener"}
             (let [alt (plain content)]
               (if (str/blank? alt) "image" alt))]]
    :html-inline [(plain content)]
    :html-block [[:p (plain content)]]
    :paragraph (element :p opts content)
    :plain (nodes opts content)
    :heading (element (keyword (str "h" (max 1 (min 6 (or heading-level 1))))) opts content)
    :bullet-list (element :ul opts content)
    :numbered-list [(into (if-let [start (:start attrs)]
                            [:ol {:start start}]
                            [:ol])
                          (nodes opts content))]
    :list-item (element :li opts content)
    :blockquote (element :blockquote opts content)
    :code [[:pre [:code (plain content)]]]
    :ruler [[:hr]]
    :table (element :table opts content)
    :table-head (element :thead opts content)
    :table-body (element :tbody opts content)
    :table-row (element :tr opts content)
    :table-header (element :th opts content)
    :table-data (element :td opts content)
    (:toc :footnotes :footnote :sidenote) []
    (if content
      (nodes opts content)
      [])))

(defn children
  "The markdown `text` as a seq of hiccup children. `:leaf` renders a text
  leaf as a seq of children; the default keeps it as it is."
  ([text]
   (children text {}))
  ([text {:keys [leaf] :or {leaf list}}]
   ;; A seq, never a vector: Replicant reads a vector child that does not
   ;; start with a keyword as text.
   (or (seq (nodes {:leaf leaf}
                   (:content (md/parse (str text)))))
       ())))

(comment
  (children "Built **index.html**, see `cat x` and [docs](http://x.y).\n\n- one\n- two")
  (children "At 0:21.5 the cut" {:leaf (fn [text] (list [:em text]))})
  )
