(ns game.core.commands
  (:require
    [game.core.board :refer [all-installed get-zones server->zone]]
    [game.core.card :refer [agenda? can-be-advanced? corp? get-card has-subtype? ice? in-hand? installed? map->Card rezzed? runner?]]
    [game.core.damage :refer [damage]]
    [game.core.drawing :refer [draw]]
    [game.core.eid :refer [effect-completed make-eid]]
    [game.core.events :refer [trigger-event]]
    [game.core.flags :refer [is-scored?]]
    [game.core.hosting :refer [host]]
    [game.core.identities :refer [disable-identity]]
    [game.core.initializing :refer [card-init deactivate make-card]]
    [game.core.installing :refer [corp-install runner-install]]
    [game.core.moving :refer [move swap-ice swap-installed trash]]
    [game.core.prompts :refer [show-prompt]]
    [game.core.props :refer [set-prop]]
    [game.core.psi :refer [psi-game]]
    [game.core.resolve-ability :refer [resolve-ability]]
    [game.core.rezzing :refer [rez]]
    [game.core.runs :refer [end-run jack-out]]
    [game.core.say :refer [system-msg]]
    [game.core.servers :refer [zones->sorted-names]]
    [game.core.set-up :refer [build-card]]
    [game.core.to-string :refer [card-str]]
    [game.core.toasts :refer [show-error-toast toast]]
    [game.core.trace :refer [init-trace]]
    [game.core.winning :refer [clear-win]]
    [game.macros :refer [continue-ability effect msg req]]
    [game.utils :refer [dissoc-in quantify safe-split same-card? same-side? server-card string->num]]
    [jinteki.utils :refer [str->int]]
    [clojure.string :as string]))

(defn- set-adv-counter [state side target value]
  (set-prop state side target :advance-counter value)
  (system-msg state side (str "sets advancement counters to " value " on "
                              (card-str state target)))
  (trigger-event state side :advancement-placed target))

(defn command-adv-counter [state side value]
  (resolve-ability state side
                   {:effect (effect (set-adv-counter target value))
                    :choices {:card (fn [t] (same-side? (:side t) side))}}
                   (map->Card {:title "/adv-counter command"}) nil))

(defn command-counter-smart [state side args]
  (resolve-ability
    state side
    {:choices {:card (fn [t] (same-side? (:side t) side))}
     :effect (req (let [existing (:counter target)
                        value (if-let [n (string->num (first args))] n 0)
                        counter-type (cond (= 1 (count existing)) (first (keys existing))
                                     (can-be-advanced? target) :advance-counter
                                     (and (agenda? target) (is-scored? state side target)) :agenda
                                     (and (runner? target) (has-subtype? target "Virus")) :virus)
                        advance (= :advance-counter counter-type)]
                    (when (not (neg? value))
                      (cond
                        advance
                        (set-adv-counter state side target value)

                        (not counter-type)
                        (toast state side
                               (str "Could not infer what counter type you mean. Please specify one manually, by typing "
                                    "'/counter TYPE " value "', where TYPE is advance, agenda, credit, power, or virus.")
                               "error" {:time-out 0 :close-button true})

                        :else
                        (do (set-prop state side target :counter (merge (:counter target) {counter-type value}))
                            (system-msg state side (str "sets " (name counter-type) " counters to " value " on "
                                                        (card-str state target))))))))}
    (map->Card {:title "/counter command"}) nil))

(defn command-facedown [state side]
  (resolve-ability state side
                   {:prompt "Select a card to install facedown"
                    :choices {:card #(and (runner? %)
                                          (in-hand? %))}
                    :effect (effect (runner-install target {:facedown true}))}
                   (map->Card {:title "/faceup command"}) nil))

(defn command-counter [state side args]
  (cond
    (empty? args)
    (command-counter-smart state side `("1"))

    (= 1 (count args))
    (command-counter-smart state side args)

    :else
    (let [typestr (.toLowerCase (first args))
          value (if-let [n (string->num (second args))] n 1)
          one-letter (if (<= 1 (.length typestr)) (.substring typestr 0 1) "")
          two-letter (if (<= 2 (.length typestr)) (.substring typestr 0 2) one-letter)
          counter-type (cond (= "v" one-letter) :virus
                             (= "p" one-letter) :power
                             (= "c" one-letter) :credit
                             (= "ag" two-letter) :agenda
                             :else :advance-counter)
          advance (= :advance-counter counter-type)]
      (when (not (neg? value))
        (if advance
          (command-adv-counter state side value)
          (resolve-ability state side
                           {:effect (effect (set-prop target :counter (merge (:counter target) {counter-type value}))
                                            (system-msg (str "sets " (name counter-type) " counters to " value " on "
                                                             (card-str state target))))
                            :choices {:card (fn [t] (same-side? (:side t) side))}}
                           (map->Card {:title "/counter command"}) nil))))))

(defn command-rezall
  [state side]
  (resolve-ability state side
    {:optional {:prompt "Rez all cards and turn cards in archives faceup?"
                :yes-ability {:effect (req
                                        (swap! state update-in [:corp :discard] #(map (fn [c] (assoc c :seen true)) %))
                                        (doseq [c (all-installed state side)]
                                          (when-not (rezzed? c)
                                            (rez state side c {:ignore-cost :all-costs :force true}))))}}}
    (map->Card {:title "/rez-all command"}) nil))

(defn command-roll [state side value]
  (system-msg state side (str "rolls a " value " sided die and rolls a " (inc (rand-int value)))))

(defn command-undo-click
  "Resets the game state back to start of the click"
  [state side]
  (when-let [click-state (:click-state @state)]
    (when (= (:active-player @state) side)
      (reset! state (assoc click-state :log (:log @state) :click-state click-state :run nil))
      (doseq [s [:runner :corp]]
        (toast state s "Game reset to start of click")))))

(defn command-undo-turn
  "Resets the entire game state to how it was at end-of-turn if both players agree"
  [state side]
  (when-let [turn-state (:turn-state @state)]
    (swap! state assoc-in [side :undo-turn] true)
    (when (and (-> @state :runner :undo-turn) (-> @state :corp :undo-turn))
      (reset! state (assoc turn-state :log (:log @state) :turn-state turn-state))
      (doseq [s [:runner :corp]]
        (toast state s "Game reset to start of turn")))))

(defn command-unique
  "Toggles :uniqueness of the selected card"
  [state side]
  (resolve-ability state side
                   {:effect (effect (set-prop target :uniqueness (not (:uniqueness target))))
                    :msg (msg "make " (card-str state target)
                              (when (:uniqueness target) " not") ;it was unique before
                              " unique")
                    :choices {:card (fn [t] (same-side? (:side t) side))}}
                   (map->Card {:title "/unique command" :side side}) nil))

(defn command-close-prompt [state side]
  (when-let [fprompt (-> @state side :prompt first)]
    (swap! state update-in [side :prompt] rest)
    (swap! state dissoc-in [side :selected])
    (effect-completed state side (:eid fprompt))))

(defn command-install-ice
  [state side]
  (when (= side :corp)
    (resolve-ability
      state side
      {:prompt "Select a piece of ice to install"
       :choices {:card #(and (ice? %)
                             (#{[:hand]} (:zone %)))}
       :effect (effect
                 (continue-ability
                   (let [chosen-ice target]
                     {:prompt "Choose a server"
                      :choices (req servers)
                      :effect (effect
                                (continue-ability
                                  (let [chosen-server target
                                        num-ice (count (get-in (:corp @state)
                                                               (conj (server->zone state target) :ices)))]
                                    {:prompt "Which position to install in? (0 is innermost)"
                                     :choices (vec (reverse (map str (range (inc num-ice)))))
                                     :effect (effect (corp-install chosen-ice chosen-server
                                                                   {:no-install-cost true
                                                                    :index (str->int target)}))})
                                  card nil))})
                   card nil))}
      (map->Card {:title "/install-ice command"}) nil)))

(defn command-peek
  [state side n]
  (show-prompt
    state side
    nil
    (str "The top " (quantify n "card")
         " of your deck " (if (< 1 n) "are" "is") " (top first): "
         (->> (get-in @state [side :deck])
              (take n)
              (map :title)
              (string/join ", ")))
    ["Done"]
    identity
    {:priority 10}))

(defn command-summon
  [state side args]
  (let [s-card (server-card (string/join " " args))
        card (when (and s-card (same-side? (:side s-card) side))
               (build-card s-card))]
    (when card
      (swap! state update-in [side :hand] #(concat % [(assoc card :zone [:hand])])))))

(defn command-replace-id
  [state side args]
  (let [s-card (server-card (string/join " " args))
        card (when (and s-card (same-side? (:side s-card) side))
               (build-card s-card))]
    (when card
      (let [new-id (-> card :title server-card make-card (assoc :zone [:identity] :type "Identity"))]
        (disable-identity state side)
        (swap! state assoc-in [side :identity] new-id)
        (card-init state side new-id {:resolve-effect true :init-data true})))))

(defn command-host
  [state side]
  (let [f (if (= :corp side) corp? runner?)]
    (resolve-ability
      state side
      {:prompt "Select the card to be hosted"
       :choices {:card #(and (f %)
                             (installed? %))}
       :async true
       :effect (effect
                 (continue-ability
                   (let [h1 target]
                     {:prompt "Select the card to host the first card"
                      :choices {:card #(and (f %)
                                            (installed? %)
                                            (not (same-card? % h1)))}
                      :effect (effect (host target h1 nil))})
                   nil nil))}
      nil nil)))

(defn command-trash
  [state side]
  (let [f (if (= :corp side) corp? runner?)]
    (resolve-ability
      state side
      {:prompt "Select a card to trash"
       :choices {:card #(f %)}
       :effect (effect (trash eid target {:unpreventable true}))}
      nil nil)))

(defn parse-command
  [text]
  (let [[command & args] (safe-split text #" ")
        value (if-let [n (string->num (first args))] n 1)
        num   (if-let [n (-> args first (safe-split #"#") second string->num)] (dec n) 0)]
    (if (= (ffirst args) \#)
      (case command
        "/deck"       #(move %1 %2 (nth (get-in @%1 [%2 :hand]) num nil) :deck {:front true})
        "/discard"    #(move %1 %2 (nth (get-in @%1 [%2 :hand]) num nil) :discard)
        nil)
      (case command
        "/adv-counter" #(command-adv-counter %1 %2 value)
        "/bp"         #(swap! %1 assoc-in [%2 :bad-publicity :base] (max 0 value))
        "/card-info"  #(resolve-ability %1 %2
                                        {:effect (effect (system-msg (str "shows card-info of "
                                                                          (card-str state target)
                                                                          ": " (get-card state target))))
                                          :choices {:card (fn [t] (same-side? (:side t) %2))}}
                                        (map->Card {:title "/card-info command"}) nil)
        "/clear-win"  clear-win
        "/click"      #(swap! %1 assoc-in [%2 :click] (max 0 value))
        "/close-prompt" command-close-prompt
        "/counter"    #(command-counter %1 %2 args)
        "/credit"     #(swap! %1 assoc-in [%2 :credit] (max 0 value))
        "/deck"       #(toast %1 %2 "/deck number takes the format #n")
        "/discard"    #(toast %1 %2 "/discard number takes the format #n")
        "/discard-random" #(move %1 %2 (rand-nth (get-in @%1 [%2 :hand])) :discard)
        "/draw"       #(draw %1 %2 (max 0 value))
        "/end-run"    (fn [state side]
                        (when (and (= side :corp)
                                    (:run @state))
                          (end-run state side (make-eid state) nil)))
        "/error"      show-error-toast
        "/facedown"   #(when (= %2 :runner) (command-facedown %1 %2))
        "/handsize"   #(swap! %1 assoc-in [%2 :hand-size :mod] (- value (get-in @%1 [%2 :hand-size :base])))
        "/host"       command-host
        "/install-ice" command-install-ice
        "/jack-out"   (fn [state side]
                        (when (and (= side :runner)
                                    (:run @state))
                          (jack-out state side (make-eid state))))
        "/link"       (fn [state side]
                        (when (= side :runner)
                          (swap! state assoc-in [:runner :link] (max 0 value))))
        "/memory"     (fn [state side]
                        (when (= side :runner)
                          (swap! state assoc-in [:runner :memory :used]
                                  (- (+ (get-in @state [:runner :memory :base])
                                        (get-in @state [:runner :memory :mod]))
                                    value))))
        "/move-bottom"  #(resolve-ability %1 %2
                                          {:prompt "Select a card in hand to put on the bottom of your deck"
                                            :effect (effect (move target :deck))
                                            :choices {:card (fn [t] (and (same-side? (:side t) %2)
                                                                        (in-hand? t)))}}
                                          (map->Card {:title "/move-bottom command"}) nil)
        "/move-deck"   #(resolve-ability %1 %2
                                          {:prompt "Select a card to move to the top of your deck"
                                          :effect (req (let [c (deactivate %1 %2 target)]
                                                          (move %1 %2 c :deck {:front true})))
                                          :choices {:card (fn [t] (same-side? (:side t) %2))}}
                                          (map->Card {:title "/move-deck command"}) nil)
        "/move-hand"  #(resolve-ability %1 %2
                                        {:prompt "Select a card to move to your hand"
                                          :effect (req (let [c (deactivate %1 %2 target)]
                                                        (move %1 %2 c :hand)))
                                          :choices {:card (fn [t] (same-side? (:side t) %2))}}
                                        (map->Card {:title "/move-hand command"}) nil)
        "/peek"       #(command-peek %1 %2 value)
        "/psi"        #(when (= %2 :corp) (psi-game %1 %2
                                                    (map->Card {:title "/psi command" :side %2})
                                                    {:equal  {:msg "resolve equal bets effect"}
                                                      :not-equal {:msg "resolve unequal bets effect"}}))
        "/replace-id" #(command-replace-id %1 %2 args)
        "/rez"        #(when (= %2 :corp)
                          (resolve-ability %1 %2
                                          {:effect (effect (rez target {:ignore-cost :all-costs :force true}))
                                            :choices {:card (fn [t] (same-side? (:side t) %2))}}
                                          (map->Card {:title "/rez command"}) nil))
        "/rez-all"    #(when (= %2 :corp) (command-rezall %1 %2))
        "/rfg"        #(resolve-ability %1 %2
                                        {:prompt "Select a card to remove from the game"
                                          :effect (req (let [c (deactivate %1 %2 target)]
                                                        (move %1 %2 c :rfg)))
                                          :choices {:card (fn [t] (same-side? (:side t) %2))}}
                                        (map->Card {:title "/rfg command"}) nil)
        "/roll"       #(command-roll %1 %2 value)
        "/summon"     #(command-summon %1 %2 args)
        "/swap-ice"   #(when (= %2 :corp)
                          (resolve-ability
                            %1 %2
                            {:prompt "Select two installed ice to swap"
                            :choices {:max 2
                                      :all true
                                      :card (fn [c] (and (installed? c)
                                                          (ice? c)))}
                            :effect (effect (swap-ice (first targets) (second targets)))}
                            (map->Card {:title "/swap-ice command"}) nil))
        "/swap-installed" #(when (= %2 :corp)
                              (resolve-ability
                                %1 %2
                                {:prompt "Select two installed non-ice to swap"
                                :choices {:max 2
                                          :all true
                                          :card (fn [c] (and (installed? c)
                                                              (corp? c)
                                                              (not (ice? c))))}
                                :effect (effect (swap-installed (first targets) (second targets)))}
                                (map->Card {:title "/swap-installed command"}) nil))
        "/tag"        #(swap! %1 assoc-in [%2 :tag :base] (max 0 value))
        "/take-brain" #(when (= %2 :runner) (damage %1 %2 :brain (max 0 value)))
        "/take-meat"  #(when (= %2 :runner) (damage %1 %2 :meat  (max 0 value)))
        "/take-net"   #(when (= %2 :runner) (damage %1 %2 :net   (max 0 value)))
        "/trace"      #(when (= %2 :corp) (init-trace %1 %2
                                                      (map->Card {:title "/trace command" :side %2})
                                                      {:base (max 0 value)
                                                        :msg "resolve successful trace effect"}))
        "/trash"      command-trash
        "/undo-click" #(command-undo-click %1 %2)
        "/undo-turn"  #(command-undo-turn %1 %2)
        "/unique"     #(command-unique %1 %2)
        nil))))