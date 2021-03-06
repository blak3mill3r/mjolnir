(ns mjolnir.targets.amd64
  (:require
   [mjolnir.expressions :as expr]
   [mjolnir.targets.target :as target]
            [clojure.java.shell :as shell]
            [mjolnir.types :as types]
            [mjolnir.targets.x86-cpus :as x86-cpus]
            [mjolnir.llvmc :as llvm])
  (:import [com.sun.jna Native Pointer Memory Function]))


(defrecord Amd64Target [march vendor os llvm-target]
  target/ITarget
  (pointer-type [this]
    (types/->IntegerType 32 #_(* (llvm/PointerSize (:target llvm-target)) 8)))
  (default-address-space [this]
    0)
  (get-calling-conv [this extern?]
    (if extern?
      llvm/CCallConv
      #_llvm/X86FastcallCallConv
      llvm/CCallConv
      #_llvm/FastCallConv))
  (create-target-machine [this opts]
    (llvm/CreateTargetMachine (:target llvm-target)
                              (str march "-" vendor "-" os)
                              (or (x86-cpus/cpus (:cpu opts)) "generic")
                              (or (:features opts) "")
                              (or (target/code-gen-levels (:code-gen-level opts))
                                  llvm/LLVMCodeGenLevelDefault)
                              (or (target/reloc-modes (:reloc-mode opts))
                                  llvm/LLVMRelocDefault)
                              (or (target/code-models (:code-model opts))
                                  llvm/LLVMCodeModelDefault)))
  (emit-to-file [this module opts]
    (let [tm (target/create-target-machine this opts)
          err (llvm/new-pointer)
          file (or (:filename opts)
                   (llvm/temp-file "amd64_output" (case (:output-type opts)
                                                     :asm ".s"
                                                     :obj ".o"
                                                     ".o")))]
      (when (llvm/TargetMachineEmitToFile tm
                                          module
                                          (name file)
                                          (or (target/output-types (:output-type opts))
                                              llvm/LLVMAssemblyFile)
                                          err)
        (assert false (.getString (llvm/value-at err) 0)))
      (llvm/DisposeMessage (llvm/value-at err))
      file))
  (as-exe [this module opts]
    (let [f (target/emit-to-file this module (assoc (dissoc opts :filename) :output-type :asm))
          cmds (list* "cc" f "-o"
                      (or (:filename opts)
                          "a.out")
                      (:link-ops opts))]
      (when (:verbose opts)
        (println "Linking: " cmds))
      (apply shell/sh cmds)))
  (as-dll [this module opts]
    (let [f (target/emit-to-file this module (assoc (dissoc opts :filename) :output-type :asm))
          opts (merge {:filename (llvm/temp-file "amd64_dll" ".dylib")}
                      opts)
          cmds (list* "cc" "-shared" f "-o"
                      (:filename opts)
                      (:link-ops opts))]
      (println cmds)
      (when (:verbose opts)
        (println "Linking: " cmds))
      (apply shell/sh cmds)
      (reify clojure.lang.ILookup
        (valAt [this key]
          (.valAt this key nil))
        (valAt [this key not-found]
          (let [nm (-> key :fn/name)
                _ (assert nm (str "Bad entity " key))
                nfn (Function/getFunction (:filename opts)
                                          nm)
                mj-ret (-> key :fn/type :type.fn/return :node/type)
                rettype (case mj-ret
                          :type/int Integer
                          :type/pointer Pointer)]
            (fn [& args]
              (.invoke nfn rettype (to-array args)))))))))

(defn get-march []
  (System/getProperty "os.arch"))

(defn get-vendor []
  "apple")

(defn get-os []
  (let [r (shell/sh "uname" "-r")]
    (str "amd64" r)))

(defn make-default-target []
  (let [t (case (get-march)
            "amd64" "x86-64"
            "x86_64" "x86-64"
            "x86" "x86")]
    (map->Amd64Target
     {:march (get-march)
      :vendor (get-vendor)
      :os (get-os)
      :llvm-target (target/find-llvm-target-by-name t)})))

(defn init-target [register-fn]
  (llvm/InitializeX86TargetInfo)
  (llvm/InitializeX86Target)
  (llvm/InitializeX86TargetMC)
  (llvm/InitializeX86AsmPrinter)
  (llvm/InitializeX86AsmParser)
  (register-fn make-default-target))
