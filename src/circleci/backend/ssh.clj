(ns circleci.backend.ssh
  (:require [clj-ssh.ssh :as ssh]))

(defn slurp-stream
  "given an input stream, read as much as possible and return it"
  [stream]
  (when (pos? (.available stream))
    (let [buffer-size clj-ssh.ssh/*piped-stream-buffer-size*
          bytes (byte-array buffer-size)
          num-read (.read stream bytes 0 buffer-size)
          s (String. bytes 0 num-read "UTF-8")]
      s)))

(defn process-exec
  "Takes the exec map and processes it"
  [[shell stdout-stream stderr-stream]]
  (let [stdout (StringBuilder.)
        stderr (StringBuilder.)
        slurp-streams (fn slurp-streams []
                        (when-let [s (slurp-stream stdout-stream)]
                          (.append stdout s)
                          (print s))
                        (when-let [s (slurp-stream stderr-stream)]
                          (.append stderr s)
                          (print s)))]
    (while (= -1 (-> shell (.getExitStatus)))
      (slurp-streams)
      (Thread/sleep 100))
    (slurp-streams)
    {:exit (-> shell .getExitStatus)
     :out (str stdout)
     :err (str stderr)}))

(defn with-session* [node f]
  (ssh/with-ssh-agent [(pallet.execute/default-agent)]
    (let [server (pallet.compute/node-address (-> node :node))
          user (-> node :admin-user)
          _ (pallet.execute/possibly-add-identity
             ssh/*ssh-agent* (:private-key-path user) (:passphrase user))
          ssh-session (ssh/session server
                                   :username (:username user)
                                   :password (:password user)
                                   :strict-host-key-checking :no)]
      (ssh/with-connection ssh-session
        (f ssh-session)))))

(defmacro with-session
  "Creates an SSH session to node, and calls body. session is the name of the local variable that will be bound to the session"
  [node session & body]
  `(with-session* ~node
     (fn [~session]
       ~@body)))

(defn remote-exec
  "Run an ssh exec command on a server."
  [node #^String command]
  (with-session node ssh-session
    (process-exec
     (ssh/ssh-exec ssh-session
                   command
                   nil
                   :stream
                   {}))))

(defn ssh-exec
  "Executes a command on a node. Takes a node returned by (node-info),
  and command, a string. Returns a map containing the keys :out, :error :exit
"
  [node cmd & {:keys [sudo]}]
  (remote-exec node cmd ))