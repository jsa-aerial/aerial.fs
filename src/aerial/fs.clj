(ns aerial.fs
  ^{:doc "File system utilities in Clojure"
    :author "Miki Tebeka <miki.tebeka@gmail.com>, Jon Anthony"}
  (:refer-clojure :exclude [empty? pop])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import java.io.File
           [java.nio.file FileSystems Path Files]
           java.io.FileInputStream
           java.io.FileOutputStream
           java.io.FilenameFilter))


(def separator File/separator)

(defn- replace-re
  [re replacement s]
  (str/replace s re replacement))


(defn ^File file
  "Returns a java.io.File from string or file args."
  {:deprecated "1.2"}
  ([arg]
     (io/as-file arg))
  ([parent child]
     (File. ^File (io/as-file parent) ^String (io/as-relative-path child)))
  ([parent child & more]
     (reduce file (file parent child) more)))


(defn fullpath
  "Canonicalize FILESPEC, a string, to a fully qualified file path
   for the native system.  ~ in position one is translated to the
   users home directory path, / and \\ are translated to the file
   separator for the native system."
  [filespec-str]
  (let [^String s (str filespec-str)
        matchstg (if (= separator "/") "\\" "/")
        s (replace-re matchstg separator s)]
    (cond
     (= s "~") (System/getProperty "user.home")

     (.startsWith s "~")
     (str (System/getProperty "user.home")
          (if (= (.charAt s 1) File/separatorChar) "" separator)
          (subs s 1))

     :else s)))


(defn join
  "Join parts of path.\n\t(join [\"a\" \"b\"]) -> \"a/b\""
  [& parts]
  (apply str (interpose separator parts)))

(defn split
  "Split path to componenets.\n\t(split \"a/b/c\") -> (\"a\" \"b\" \"c\")"
  [path]
  (into [] (.split path separator)))

(defn rename
  "Rename old-path to new-path."
  [old-path new-path]
  (.renameTo (file old-path) (file new-path)))


(declare size)

(defn exists?
  "Return true if path exists."
  [path]
  (.exists (file path)))

(defn empty? [path]
  "Returns false if either (file path) does not exist OR if the
   denoted file is empty (has size 0)"
  (or (not (exists? path))
      (= (size path) 0)))

(defn directory?
  "Return true if path is a directory."
  [path]
  (.isDirectory (file path)))

(defn file?
  "Return true if path is a file."
  [path]
  (.isFile (file path)))

(defn executable?
  "Return true if path is executable."
  [path]
  (.canExecute (file path)))

(defn readable?
  "Return true if path is readable."
  [path]
  (.canRead (file path)))

(defn writeable?
  "Return true if path is writeable."
  [path]
  (.canWrite (file path)))

(defn symbolic-link?
  "Return true if path denotes a symbolic link, false otherwise"
  [path]
  (Files/isSymbolicLink
   (-> (FileSystems/getDefault)
       (.getPath path (into-array [""])))))


(defn pwd "Return current working directory path" []
  (System/getProperty "user.dir"))

(defn homedir "Return user home directory path" []
  (System/getProperty "user.home"))

(defn cd
  "Change working directory to DIR and return previous working
   directory. If DIR does not exist do nothing and return nil
  "
  [dir]
  (let [dir (fullpath dir)]
    (when (exists? dir)
      (System/setProperty "user.dir" dir))))

(def ^:dynamic dir-stack (atom ()))

(defn dirstack "Return current directory stack" []
  @dir-stack)

(defn push
  "Push current working directory on directory stack, cd to DIR, and
   return previous (saved) working directory. If DIR does not exist do
   nothing and return nil.
  "
  [dir]
  (let [dir (fullpath dir)
        cur (fullpath (pwd))]
    (when (exists? dir)
      (swap! dir-stack conj cur)
      (cd dir))))

(defn pop
  "Pop current top saved directory from directory stack, make it the
   current working directory, and return prior working directory. If
   directory stack is empty, do nothing and return nil.
  "
  []
  (when (seq @dir-stack)
    (let [dir (first @dir-stack)]
      (swap! dir-stack clojure.core/pop)
      (cd dir))))


;;; From clj-file-utils
(defn delete
  "Delete path."
  [path]
  (io/delete-file (file path)))

(defn rm
  "Remove a file. Will throw an exception if the file cannot be deleted."
  [file]
  (io/delete-file file))

(defn rm-f
  "Remove a file, ignoring any errors."
  [file]
  (io/delete-file file true))

(defn rm-r
  "Remove a directory. The directory must be empty; will throw an
   exception if it is not or if the file cannot be deleted.
  "
  [path &[silently]]
  (let [f (file path)]
    (if (.isDirectory f)
      (doseq [child (.listFiles f)]
        (rm-r child silently)))
    (io/delete-file f silently)))

(defn rm-rf
  "Remove a directory, ignoring any errors."
  [path]
  (rm-r path true))


(defn abspath
  "Return absolute path."
  [path]
  (.getAbsolutePath (file path)))

(defn- strinfify [file]
  (.getCanonicalPath file))

(defn normpath
  "Return nomralized (canonical) path."
  [path]
  (strinfify (file path)))

(defn basename
  "Return basename (file part) of path.\n\t(basename \"/a/b/c\") -> \"c\""
  [path]
  (.getName (file path)))

(defn dirname
  "Return directory name of path.\n\t(dirname \"a/b/c\") -> \"/a/b\""
  [path]
  (.getParent (file path)))

(defn ftype
  "Return the file type suffix of PATH.  File type suffix is the last
   substring element following a '.' in (basename PATH).  If no such
   element is in the basename, returns empty string\"\"

   Ex: (ftype \"/home/fred/Bio/test.sto\") ==> \"sto\"
  "
  [path]
  (let [bn (basename path)
        ft (last (str/split bn #"\."))]
    (if (= ft bn) "" ft)))

(defn replace-type
  "Replace the file extension type of FILESPEC to be EXT.  The type
   for FILESPEC is the last part dotted extension.  Formally, matches
   regexp '\\.[^.]*$'.  If EXT is a seq/vec replace extensions in
   last/first pairings.  Last extension replace by (first EXT), then
   last of that result is replaced by (second EXT), etc."
  [filespec ext]
  (let [rep-type (fn [filespec ext]
                   (let [dir (dirname filespec)
                         fname (replace-re #"\.[^.]*$" ext
                                           (basename filespec))]
                     (if dir (str dir separator fname) fname)))]
    (reduce #(rep-type %1 %2) filespec (if (coll? ext) ext [ext]))))



(defn mtime
  "Return file modification time."
  [path]
  (.lastModified (file path)))

(defn size
  "Return size (in bytes) if file."
  [path]
  (.length (file path)))




(defn listdir
  "List files under path."
  [path]
  (seq (.list (file path))))

(defn- _re-dir-files [directory pat]
  (map #(join directory %)
       (filter #(re-find pat %) (listdir directory))))

(defn- fix-file-regex [l]
  (-> l str/trim (str "$") ((partial replace-re #"\*" ".*"))))

(defn re-directory-files
  "Return full path qualified file specifications for all files in
   directory whose name ends with a string matched by re.  RE is a
   regexp (#\"regex def\" literal or a string that defines a
   regexp (which will be turned into a pattern).
  "
  [directory re]
  (->> re str fix-file-regex re-pattern (_re-dir-files directory)))

(defn directory-files
  "Return full path qualified file specifications for all files in
   directory whose suffix matches file-type.  Typically file-type
   would be the type suffix (e.g., .txt or .sto or .fna or .clj or
   whatever), but could include any part of the file name's suffix.
   So, giving -new.sto for example, works as well.
  "
  [directory file-type]
  ;; REFACTOR to ues re-directory-files???
  (_re-dir-files directory (re-pattern (str file-type "$"))))




(defn mkdir
  "Create a directory."
  [path]
  (.mkdir (file path)))

(defn mkdirs
  "Make directory tree."
  [path]
  (.mkdirs (file path)))

(defn copy [from to]
  (let [from (file from)
        to (file to)]
    (when (not (.exists to)) (.createNewFile to))
    (with-open [to-channel (.getChannel (FileOutputStream. to))
                from-channel (.getChannel (FileInputStream. from))]
      (.transferFrom to-channel from-channel 0 (.size from-channel)))))


(defn dodir
  "Map ACTIONF over application of FILTERF to DIR.

   FILTERF is a function that operates on a directory returning a
   result set (typically a set of files).  ACTIONF is a function that
   takes an element of the set and any ancillary supplied arguments
   ARGS and returns an appropriate result.

   Returns the seq of results of ACTIONF minus any nils"
  [dir filterf actionf & args]
  (let [files (filterf dir)]
    (keep #(when-let [r (apply actionf % args)] r) files)))


(defn cpfiles
  "Copy a set of files from a set of dirs DIRDIR to an output dir OUTDIR.

   FILE-TYPE is the type of files in each dir in DIRDIR that are
   candidates to be copied.  REGEX is a regular expression to filter
   the set of candidates

   EX: (cpfiles \"/data2/Bio/Training/MoStos2\" \"/home/jsa/TMP/X\"
                 #\"firm\" \".sto\")
  "
  [dirdir outdir regex file-type]
  (flatten
   (dodir dirdir
          #(directory-files % "")
          (fn[d]
            (dodir d #(directory-files % file-type)
                   #(when (re-find regex %)
                      (let [to (join outdir (basename %))]
                        (copy % to) to)))))))



; FIXME: Write this
; (defn copytree [from to] ...

(defn tempfile
  "Create a temporary file."
  ([] (tempfile "-fs-" ""))
  ([prefix] (tempfile prefix ""))
  ([prefix suffix] (.getAbsolutePath (File/createTempFile prefix suffix)))
  ([prefix suffix directory]
   (.getAbsolutePath (File/createTempFile prefix suffix (File. directory)))))

(defn tempdir
  "Create a temporary directory"
  ([] (let [dir (File/createTempFile "-fs-" "")
            path (.getAbsolutePath dir)]
        (.delete dir)
        (.mkdir dir)
        path))
  ([root]
   (let [dir (File/createTempFile "-fs-" "" (File. root))
         path (.getAbsolutePath dir)]
     (.delete dir)
     (.mkdir dir)
     path)))




; Taken from https://github.com/jkk/clj-glob. (thanks Justin!)
(defn glob->regex
  "Takes a glob-format string and returns a regex."
  [s]
  (loop [stream s
         re ""
         curly-depth 0]
    (let [[c j] stream]
        (cond
         (nil? c) (re-pattern (str (if (= \. (first s)) "" "(?=[^\\.])")
                                   re ; if s didn't end in *, force eol
                                   (if (= \* (last re)) "" "$")))
         (= c \\) (recur (nnext stream) (str re c c) curly-depth)
         (= c \/) (recur (next stream) (str re (if (= \. j) c "/(?=[^\\.])"))
                         curly-depth)
         (= c \*) (recur (next stream) (str re "[^/]*") curly-depth)
         (= c \?) (recur (next stream) (str re "[^/]") curly-depth)
         (= c \{) (recur (next stream) (str re \() (inc curly-depth))
         (= c \}) (recur (next stream) (str re \)) (dec curly-depth))
         (and (= c \,) (< 0 curly-depth)) (recur (next stream) (str re \|)
                                                 curly-depth)
         (#{\. \( \) \| \+ \^ \$ \@ \%} c) (recur (next stream) (str re \\ c)
                                                  curly-depth)
         :else (recur (next stream) (str re c) curly-depth)))))

(defn glob [pattern]
  "Returns files matching glob pattern."
  (let [parts (split pattern)
        root (if (= (count parts) 1) "." (apply join (butlast parts)))
        regex (glob->regex (last parts))]
    (map #(join root %) (filter #(re-find regex %) (listdir root)))))


;; (ns-unmap *ns* 'assert-files?)
(defmulti
  ^{:arglists
    '([coll]
      [regex]
      [file-glob])}
  assert-files?
  "Assert that the files (including directories) designated by
   DESIGNATOR exist.  If not, collect all those that do not and raise
   an ex-info exeception :type :no-such-files :files <set of names>.

   DESIGNATOR can be a collection, regex or string.  If a collection,
   each element is taken as a full path of a file; if regex, uses
   re-directory-files to obtain a collection of paths; if string,
   treat as file glob and use fs/glob to obtain collection of paths.
   Check all elements of resulting collection with fs/exists?"
  (fn[designator]
    (if (coll? designator)
      :coll
      (type designator))))

(defmethod assert-files? :coll
  [coll]
  (let [bad (filter #(or (nil? %) (not (exists? %))) coll)]
    (if (seq bad)
      (throw (ex-info "No such files" {:type :no-such-files :files bad}))
      :good)))

(defmethod assert-files? String
  [gspec]
  (let [files (glob gspec)]
    (if (seq files)
      (assert-files? (glob gspec))
      (throw (ex-info "No such files" {:type :no-such-files :files gspec})))))

(defmethod assert-files? java.util.regex.Pattern
  [re]
  (let [stg (.pattern re)
        dir (dirname stg)
        re (re-pattern (basename stg))
        files (re-directory-files dir re)]
    (if (seq files)
      (assert-files? files)
      (throw (ex-info "No such files" {:type :no-such-files :files re})))))


;; (ns-unmap *ns* 'move)
(defmulti
  ^{:arglists
    '([coll newdir]
      [regex newdir]
      [file-glob newdir])}
  move
  "Move the files (including directories) designated by DESIGNATOR to
   the directory NEWDIR.  DESIGNATOR can be collection, regex or
   string.  If a collection, each element is taken as a full path of a
   file; if regex, uses re-directory-files to obtain a collection of
   paths; if string, treat as file glob and use fs/glob to obtain
   collection of paths.  Move all elements of resulting collection to
   newdir."
  (fn [designator newdir]
    (if (coll? designator)
      :coll
      (type designator))))

(defmethod move :coll
  [coll newdir]
  (doseq [f coll]
    (rename f (join newdir (basename f)))))

(defmethod move String
  [gspec newdir]
  (move (glob gspec) newdir))

(defmethod move java.util.regex.Pattern
  [re newdir]
  (let [stg (.pattern re)
        dir (dirname stg)
        re (re-pattern (basename stg))]
    (move (re-directory-files dir re) newdir)))

