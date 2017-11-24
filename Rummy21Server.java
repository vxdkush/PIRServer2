package com.hongkong.game.casino;

import java.io.IOException;
import java.security.Key;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Stack;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.apache.log4j.Logger;

import com.atlantis.util.Rng;
import com.atlantis.util.Utils;
import com.hongkong.common.db.DBEAP;
import com.hongkong.common.db.DBException;
import com.hongkong.common.db.GameRunSession;
import com.hongkong.common.message.ResponseGameEvent;
import com.hongkong.game.Game;
import com.hongkong.game.GameType;
import com.hongkong.game.Moves;
import com.hongkong.game.Player;
import com.hongkong.game.Player.Presence;
import com.hongkong.game.casino.Rummy500Server.RummyProfile;
import com.hongkong.game.resp.Response;
import com.hongkong.game.util.Card;
import com.hongkong.game.util.Cards;
import com.hongkong.nio.Client;
import com.hongkong.nio.Handler;
import com.hongkong.server.GamePlayer;
import com.hongkong.server.GameProcessor;
import com.atlantis.util.Base64;
public class Rummy21Server extends CasinoGame {
	static Logger _cat = Logger.getLogger(Rummy21Server.class.getName());
	String _name;
	double _minBet;
	double _maxBet;
	double totalBet = 0, totalWin = 0, totalGames = 0;
	String moveDetails = "";

	public static final int MAX_PLAYERS = 6;
	public static final int move_RUMMY_INVALID = 0;
	public static final int move_RUMMY_FOLD = 1;
	public static final int move_RUMMY_NEWCARD = 2;
	public static final int move_RUMMY_DISCARD = 4;
	public static final int move_RUMMY_DECLARE = 8;
	public static final int move_RUMMY_RUMMYCUT = 16;
	public static final int move_RUMMY_LEFT = 32;
	public static final int move_RUMMY_JOINED = 64;
	public static final int move_RUMMY_DECLARE_LOSER = 128;
	public static final int move_RUMMY_CHAT = 256;
	
	public static final int move_RUMMY_SITOUT = 512;
	public static final int move_RUMMY_SITIN = 1024;
	
	public int globalPenal = 0;
	public boolean globalFirstLife = false, globalSecondLife = false;

	public static final int status_NONE = 1;
	public static final int status_ACTIVE = 2;
	public static final int status_DECLARE_INVALID = 4;
	public static final int status_REMOVED = 8;
//	public static final int status_DEALER = 16;
//	public static final int status_WINNER = 32;
	public static final int status_SITTINGOUT = 64;
	public static final int status_FOLDED = 128;
	public static final int status_LEFT = 256;
//	public static final int status_BROKE = 512;
	public static final int status_JOINED = 1024;

	public static final int MAX_TABLES = 10;

	Rummy21Table[] _tables = null;

	boolean _keepServicing = false;

	private LinkedList[] _msgQ = new LinkedList[MAX_TABLES];

	public void add(int index, Presence cmdStr) {
		(_msgQ[index]).add(cmdStr);
	}

	public Presence fetch(int index) {
		return (Presence) (_msgQ[index].removeFirst());
	}

	public Rummy21Server(String name, double minB, double maxB, GameType type,
			int gid) {
		_gid = gid;
		_name = name;
		_minBet = minB;
		_maxBet = maxB;
		_type = type;
	//	_cat.debug(this);
		_keepServicing = true;
		// one linkedlist per thread
		for (int i = 0; i < MAX_TABLES; i++) {
			_msgQ[i] = new LinkedList();
		}
		_tables = new Rummy21Table[MAX_TABLES];
		for (int i = 0; i < MAX_TABLES; i++) {
			_tables[i] = new Rummy21Table();
			_tables[i].validTable = true;
			_tables[i].POINTSPERRUPEE = (i + 1) * 0.2;
			if (_tables[i].POINTSPERRUPEE > 1.3
					&& _tables[i].POINTSPERRUPEE < 1.6)
				_tables[i].POINTSPERRUPEE = 1.5;
			else if (_tables[i].POINTSPERRUPEE == 1.6)
				_tables[i].POINTSPERRUPEE = 2.0;
			else if (_tables[i].POINTSPERRUPEE == 1.8)
				_tables[i].POINTSPERRUPEE = 5.0;
			else if (_tables[i].POINTSPERRUPEE == 2.0)
				_tables[i].POINTSPERRUPEE = 10;
			
			int temp = (int)(_tables[i].POINTSPERRUPEE * 100);
			_tables[i].POINTSPERRUPEE = (((double)temp) / 100);

			Thread t = new Thread(_tables[i]);
			t.setName("Rummy21-Table-" + i);
			t.setPriority(Thread.NORM_PRIORITY);
			_tables[i].setIndex(i);
			t.start();

			_cat.debug("starting 21 thread : " + i);
		}
	}

	public StringBuffer gameDetail() {
		StringBuffer sb;
		sb = new StringBuffer("Rummy21Server=").append(_name)
				.append(",min-bet=").append(_minBet).append(",max-bet=")
				.append(_maxBet).append(",Rummy21Tables=");
		// now add details of all tables
		for (int i = 0; i < MAX_TABLES; i++) {
			if (_tables[i].validTable) {
				double dbl = _tables[i].POINTSPERRUPEE;
				// int intdbl = ((int) dbl / 100);
				sb.append(dbl);
				sb.append("'");
				sb.append(_tables[i]._tid).append(
						"'" + _tables[i].getMaxPlayers());
				sb.append("'" + _tables[i].getCurrentPlayersCount());
				String details = _tables[i].getCurrentPlayersDetail();
				sb.append("'" + details);

				if (i != MAX_TABLES - 1)
					sb.append(":");
			}
		}
		return sb;
	}

	class RummyProfile {

		RummyProfile() {
			// initialize other variables if there is a need
			rummyStatus = -1;
		}

		String name = "";

		public void setName(String nam) {
			name = nam;
		}

		public String getName() {
			return name;
		}

		double startWorth = 0;

		public void setGameStartWorth(double worth) {
			startWorth = worth;
		}

		public double getGameStartWorth() {
			return startWorth;
		}

		boolean reqForChipsReload = false;

		public void setReloadReq(boolean flag) {
			reqForChipsReload = flag;
		}

		public boolean getReloadReq() {
			return reqForChipsReload;
		}

		int rummyMove;

		public int getRUMMYMoveId() {
			return rummyMove;
		}

		public void setRUMMYMoveId(int mvId) {
			rummyMove = mvId;
		}

		String rummyMovesStr;

		public String getRUMMYMovesStr() {
			return rummyMovesStr;
		}

		public void setRUMMYMovesStr(String moves) {
			rummyMovesStr += moves;
		}

		public void clearRUMMYMovesStr() {
			rummyMovesStr = "";
		}

		int rummyStatus;

		public int getRUMMYStatus() {
			return rummyStatus;
		}

		public void setRUMMYStatus(int status) {
			rummyStatus = status;
		}

		public long rummyLeftTableTime = -1;

		public long rummyLastMoveTime = -1;

		int rummyPointsToPay;

		public int getRUMMYPoints() {
			return rummyPointsToPay;
		}

		public void setRUMMYPoints(int val) {
			rummyPointsToPay = val;
		}

		int rummyValueCards;

		public int getRUMMYValCards() {
			return rummyValueCards;
		}

		public void setRUMMYValCards(int val) {
			rummyValueCards = val;
		}

		int rummyValueGiven;

		public int getRUMMYValGiven() {
			return rummyValueGiven;
		}

		public void setRUMMYValGiven(int val) {
			rummyValueGiven = val;
		}

		double rummyWinAmt;

		public double getRUMMYWinAmt() {
			return rummyWinAmt;
		}

		public void setRUMMYWinAmt(double winamt) {
			rummyWinAmt = winamt;
		}

		int _pos = -1;

		public int pos() { // _cat.debug("HI");
			return _pos;
		}

		public void setPos(int pos) { // _cat.debug("HI");
			_pos = pos;
		}

		public int _tid;

		Presence pr = null;

		public void setPresence(Presence p) {
			pr = p;
		}

		public Presence getPresence() {
			return pr;
		}
		
				
		boolean usingTimeBank = false;
		public void setUsingTimeBank(boolean ffla){
			usingTimeBank = ffla;
			if (ffla){
				decrTimeBank();
				
				setTimeBankExpTime();
			}
			_cat.debug("setusingtimebank : " + timeBankValue);
		}
		public boolean getUsingTimeBank(){
			return usingTimeBank;
		}
		
		public boolean isEligibleTimeBank(){
			if (usingTimeBank)
				return false;
			
			return timeBankValue > -1;
		}
		
		//timebankvalue goes in increments of 10 seconds for this game.
		int timeBankValue = 3;
		int timeBankValueUsed = -1;
		public void incrTimeBank(){
			if (timeBankValue >= 3)
				timeBankValue = 3;
			else
				timeBankValue++;
			
			setUsingTimeBank(false);
			timeBankValueUsed = -1;
		}
		
		public void decrTimeBank(){
			timeBankValueUsed = timeBankValue;
			
			timeBankValue--;
			if (timeBankValue < 0)
				timeBankValue = -1;
		}
		
		public void setTimeBank(long timeUsedBank){
			int val = 0;
			while (val * 10000 < timeUsedBank)
				val++;
			
			timeBankValue = val;
			timeBankValueUsed = -1;
			_cat.debug("new timebank value : " + timeBankValue);
		}
		
		public int getTimeBank(){
			return timeBankValueUsed;
		}
		
		long timeBankExpTime = 0;
		public void setTimeBankExpTime(){
			//every usage of timebank is for 10 seconds for each timebankvalue
			_cat.debug("settimebankexp : " + timeBankValueUsed);
			timeBankExpTime = System.currentTimeMillis() + 10000 * (timeBankValueUsed);
		}
		
		public long getTimeBankExpTime(){
			return timeBankExpTime;
		}

		int _state = -1;
		final static int RUMMYPLAYER = 8;

		public boolean isRummyPlayer() { // _cat.debug("HI");
			return (_state & RUMMYPLAYER) > 0;
		}

		public void setRummyPlayer() { // _cat.debug("HI");
			_state |= RUMMYPLAYER;
		}

		public void unsetRummyPlayer() { // _cat.debug("HI");
			_state &= ~RUMMYPLAYER;
		}

		public Card[] _allotedCards = null;
		public boolean _firstMoveDone = false;

		public String cardsStrFromClient = "";

		public boolean _markCheckingCards = false;

		public int fixPosCard = -1;

		public int tanalaCount = 0;
		public int jokerCount = 0, dubleeCount = 0, marriageCount = 0;
		public int pjcount = 0, highValCutJoker = 0, highValDownJoker = 0,
				highValUpJoker = 0;
		public String valueCardString;
		public double _rake;
	}

	class Rummy21Table implements Runnable {
		volatile boolean _gameOngoing = false;
		static final double RAKE_PERCENTAGE = 5;
		double POINTSPERRUPEE = 0.5;
		volatile RummyProfile[] _players = new RummyProfile[MAX_PLAYERS];
		// all the players who paid ante or who opted out of ante only - for
		// entries in grs table
		int _nextMovesAllowed;// to remember the moves that are allowed
		volatile int _nextMovePlayer = -1;
		volatile int _dealer;// to mark the very first player
		volatile int _rummyPlayer;// first one to make a move.
		int _winnerPos = -1;// outer or inner - where did the winning card fall?

		boolean fixingDealerOngoing = false;
//		boolean fixingDealerNextHand = true;
//		int countHandsAfterDealerFix = 0;

		String _winnerString = "";
		
		String _amtWonString = "";
		
		int dbtotalpoints = 0;
		
		int dbpotcontendors = 0;
		
		double _rake = 0;

		String newCardAdded = "";

		String _cardDealingOrder = "";

		// the card for which the players wage war
		Card _rummyCardJoker, _discardCard, _prevDiscardCard, _rummyUpJoker,
				_rummyDownJoker;

		String finalResultRuns = "", finalResultRuns3 = "",
				finalResultRuns1 = "", finalResultRuns2 = "";

		int idPlayerChecked = -1;

		int idPlayerValidDeclared = -1;

		int countPlayerResponseDeclareLoser = 0;

		boolean validTable = false;

		BitSet _deck = new BitSet(); // this is for fresh pile
		Card[] _discardDeck = new Card[150]; // when ever a card is discarded,
												// we
												// add it here. when one is
												// removed,
												// prev card comes to fore.
		// when the fresh pile runs out, we unset the bits in _desk that are set
		// in _discardDeck and empty out _discardDeck
		int _indexDiscardDeck;

		int totalJokersAlreadyDealt = 0;
		boolean printedJokerDealt = false;

		int NUM_DECKS = 3;
		int MAX_CARDS = 52 * NUM_DECKS - 1;

		int _lastMove;
		int _lastMovePos = -1;
		String _lastMoveString = "";

		Vector _observers = new Vector();

		long rummygrid;
		Calendar _gameStartTime;

		long _nextMoveExpTime;
		boolean noMovesToBeAccepted = false;
		
		int counterGameNotStarted = 0;

		int _tid = -1;

		volatile long lastRespTime = 0;

		BitSet bitsetJoker;
		
		String chatMessage = "";
		int chatOn = 0;
		
		boolean toSendMsg = false;
		
		//for sitting out players
		ArrayList<Integer> sitoutPlayers = new ArrayList<Integer>();
		ArrayList<Integer> sitinPlayers = new ArrayList<Integer>();

		public Rummy21Table() {
			for (int i = 0; i < MAX_PLAYERS; i++) {
				_players[i] = null;
			}
			_nextMoveExpTime = System.currentTimeMillis();
		}

		public void setIndex(int index) {
			_tid = index;
		}

		public RummyProfile findProfile(Presence p) {
			for (int k = 0; k < MAX_PLAYERS; k++) {
				if (_players[k] != null
						&& _players[k].getName().compareToIgnoreCase(p.name()) == 0) {
					return _players[k];
				}
			}
			return null;
		}

		public RummyProfile findProfile(String name) {
			for (int k = 0; k < MAX_PLAYERS; k++) {
				if (_players[k] != null
						&& _players[k].getName().compareToIgnoreCase(name) == 0) {
					return _players[k];
				}
			}
			return null;
		}

		public int addObserver(RummyProfile p) {
			// first check if he is already in this list, if so don't do
			// anything
			int i = 0;
			for (i = 0; i < _observers.size(); i++) {
				RummyProfile pro = (RummyProfile) _observers.get(i);
				if (p.getName().compareToIgnoreCase(pro.getName()) == 0)
					return i;
			}
			_observers.add(p);
			_cat.debug("adding observor : " + p.getName()
					+ " on table : " + _tid + " on pos : " + i);
			return _observers.size();
		}

		public void removeObserver(RummyProfile p) {
			_observers.remove(p);
		}

		public int findObsPos(Presence p) {
			for (int i = 0; i < _observers.size(); i++) {
				RummyProfile pro = (RummyProfile) _observers.get(i);
				_cat.debug("from list : " + pro.getName());
				if (p.name().compareToIgnoreCase(pro.getName()) == 0)
					return i;
			}
			return -1;
		}

		private RummyProfile findObservor(int pos) {
			if (pos != -1)
				return (RummyProfile) _observers.get(pos);
			else
				return null;
		}
		
		private void handleSitoutSitin(){

			//for supporting sitout and sitin. 
			//sitout means next game he wont join
			//sitin means next game he will be joining
			for (int m = 0; m < sitoutPlayers.size(); m++){
				int pos = sitoutPlayers.get(m);
				if (_players[pos] != null  && !isRemoved(_players[pos]) && !isLeft(_players[pos]) ){
					_cat.debug("active player wants to sit out. ok.");
					_players[pos].setRUMMYStatus(status_SITTINGOUT);
				}
			}
			
			sitoutPlayers.clear();
			
			for (int m = 0; m < sitinPlayers.size(); m++){
				int pos = sitinPlayers.get(m);
				if (_players[pos] != null && isSittingOut(_players[pos])){
					_cat.debug("sitting out player wants to rejoin. ok.");
					_players[pos].setRUMMYStatus(status_ACTIVE);
				}
			}
			
			sitinPlayers.clear();
		}
		
		private RummyProfile findObservor(String name) {
			if (!name.isEmpty()) {
				for (int i = 0; i < _observers.size(); i++){
					RummyProfile rp = (RummyProfile) _observers.get(i);
					if (rp.name.compareToIgnoreCase(name) == 0)
						return rp;
				}
			}
			return null;
		}

		public void resetTable() {
			//find a friend feature - clear out here
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null) {
					_players[m].getPresence().player().setActiveGame(-1);
				}
			}
			
			if (!_gameOngoing){
				handleSitoutSitin();
			}
			
			// first cull the players - this will leave some open seats
			// _cat.debug("from reset table");
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null) {
					if (isRemoved(_players[m])
							|| isLeft(_players[m])
							|| (_players[m].getPresence().getAmtAtTable() <= (120 * POINTSPERRUPEE))
					// ||!_players[m].getRUMMYBetApplied() //this condition is
					// to throw out the players who just sit on table
					) {
						//don't do anything here...
					} else {
						_players[m].clearRUMMYMovesStr();
						_players[m].setReloadReq(false);
						_players[m]._allotedCards = null;
						_players[m]._firstMoveDone = false;
						if (!isSittingOut(_players[m]))
							_players[m].setRUMMYStatus(status_ACTIVE);
						_players[m].rummyLastMoveTime = System
								.currentTimeMillis();
						_players[m].cardsStrFromClient = "";
						_players[m].getPresence().resetRoundBet();
					}
				}
			}

			//find a friend feature
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null && !isRemoved(_players[m]) && !isLeft(_players[m])) {
					_players[m].getPresence().player().setActiveGame(1);
					_players[m].getPresence().player().setActiveTable(_tid);
				}
			}
		}

		public int findPos(Presence p) {
			for (int k = 0; k < MAX_PLAYERS; k++) {
				if (_players[k] != null
						&& _players[k].getName().compareToIgnoreCase(p.name()) == 0) {
					return k;
				}
			}
			return -1;
		}

		private void initGame() {
			_cat.debug("have to come here to clear out all stuff...."
					+ " on table : " + _tid);
			finalResultRuns = "";
			finalResultRuns3 = "";
			finalResultRuns1 = "";
			finalResultRuns2 = "";
			
			_nextMovePlayer = -1;

			idPlayerChecked = -1;

			idPlayerValidDeclared = -1;

			countPlayerResponseDeclareLoser = 0;

			_nextMoveExpTime = System.currentTimeMillis();

			_deck.clear();
			_discardDeck = new Card[150];
			_indexDiscardDeck = 0;

			totalJokersAlreadyDealt = 0;
			printedJokerDealt = false;

			// find the rummy card and the last card that will never come
			int randCard = drawCard();
			// if rummy card comes to be joker, then make Ace of Clubs as joker
			if (randCard >= 161) {
				_deck.clear(randCard);
				randCard = 51;//has to be Ace of Spades
				_deck.set(randCard);
			}
			_rummyCardJoker = new Card(randCard);
			_rummyCardJoker.setIsOpened(true);

			// for up and down joker
			if (_rummyCardJoker.getRank() == Card.TWO) {
				int tein = _rummyCardJoker.getIndex() + 12;
				_rummyDownJoker = new Card(tein);
			} else {
				int tein = _rummyCardJoker.getIndex() - 1;
				_rummyDownJoker = new Card(tein);
			}

			if (_rummyCardJoker.getRank() == Card.ACE) {
				int tein = _rummyCardJoker.getIndex() - 12;
				_rummyUpJoker = new Card(tein);
			} else {
				int tein = _rummyCardJoker.getIndex() + 1;
				_rummyUpJoker = new Card(tein);
			}

			randCard = drawCard();
			_discardCard = new Card(randCard);
			_discardCard.setIsOpened(true);

			_discardDeck[_indexDiscardDeck++] = _discardCard;

			_prevDiscardCard = new Card(randCard);

			_cardDealingOrder = "";
			newCardAdded = "";
			bitsetJoker = new BitSet();
			_lastMovePos = -1;
			_lastMove = 0;
			_lastMoveString = "";
			countPlayerResponseDeclareLoser = 0;
		}

		public int getIndex() {
			return _tid;
		}

		public int getMaxPlayers() {
			return MAX_PLAYERS;
		}

		public int getCurrentPlayersCount() {
			int actualCount = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null && _players[i].isRummyPlayer())
					actualCount++;
			}
			return actualCount;
		}

		public String getCurrentPlayersDetail() {
			StringBuffer det = new StringBuffer();
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null && _players[i].isRummyPlayer()) {
					det.append(_players[i].getName() + "`"
							+ _players[i].getRUMMYStatus());
					det.append("`").append(_players[i].pos());
					det.append("|");
				}
			}
			if (det.length() > 1)
				det.deleteCharAt(det.length() - 1);
			return det.toString();
		}

		public void addPresence(RummyProfile p) {
			_players[p.pos()] = p;
		}

		public int findFirstAvailPos() {
			int i = 0;
			for (i = 0; i < MAX_PLAYERS; i++) {
				// dealer and rummyplayer even if left have to be maintained -
				// no one can sit in their place
				if (_players[i] == null)
					return i;
			}
			return -1;
		}

		public void handleGameJoinReq(int obspos, Presence pold) {
			// get the player
			RummyProfile p = null;
			boolean rejoinReq = false;

			// check if the player was playing before he decided to logout, now
			// he wants to come back
			p = findProfile(pold.name());
			if (p != null)
				rejoinReq = true;

			if (p == null)
				p = findObservor(pold.name());

			if (p == null) {
				_cat.debug("can't find the player in observors list or old players : "
								+ obspos);
				return;
			}

			// if (_gameOngoing) {
			// //game in progress, no one can join now, wait for game over
			// _cat.debug("no one joins during game!!!");
			// return;
			// }

			_cat.debug("player found : " + p.getName() + " , rejoin : "
					+ rejoinReq);
			int pos = -1;
			if (!rejoinReq) {
				int _countPlayersInit = getCountActivePlayers();
				_cat.debug("found " + _countPlayersInit
						+ " players on table ");
				if (_countPlayersInit >= 2 || fixingDealerOngoing
						|| _gameOngoing) {

					pos = adjustNextToDealer(p);
					if (pos == -1) {
						_cat.debug("despite attempts no avail pos at table :"
										+ _tid + " for player : " + p.getName());
						return;
					}
					chatOn = 0;
					chatMessage = _players[pos].name + " joined at pos : " + (pos + 1);
					
					_players[pos].getPresence().player().setActiveGame(1);
					_players[pos].getPresence().player().setActiveTable(_tid);
					
					_players[pos].setTimeBank(30000);

					_cat.debug("new nextmovepos : " + _nextMovePlayer
							+ " , new rummy player tag : " + _rummyPlayer);
					p.setRUMMYStatus(status_JOINED);
				} else {
					// less than 2 players - call resettable once
					resetTable();
					_countPlayersInit = getCountTotalPlayers();

					if (_countPlayersInit == 0) {
						pos = 0;// very first player
						_players[0] = p;
						_nextMoveExpTime = System.currentTimeMillis();
					} else if (_countPlayersInit == 1) {
						//check if the already seated plaeyr is on pos 1.
						//if so, give pos 0 to the incoming player
						//after all, there is only 1 player on table. 0 is empty
						if (_players[1] != null)
							pos = 0;
						else
							pos = 1;
						
						_players[pos] = p;
						_nextMoveExpTime = System.currentTimeMillis();
					}
					
					chatOn = 0;
					chatMessage = _players[pos].name + " joined at pos : " + (pos + 1);
					
					_players[pos].getPresence().player().setActiveGame(1);
					_players[pos].getPresence().player().setActiveTable(_tid);
					
					_players[pos].setTimeBank(30000);
					
					p.setRUMMYStatus(status_ACTIVE);// the first 2 players have
													// to be marked active
				}
			} else {
				pos = p._pos;
				if (!isFolded(p))
					p.setRUMMYStatus(status_JOINED);
				else
					p.setRUMMYStatus(status_FOLDED);//removing the REMOVED status
				
				chatOn = 0;
				chatMessage = _players[pos].name + " rejoined at pos : " + (pos + 1);
			}

			removeObserver(p);

			// now make him seat on the table on that position
			p.setPresence(pold);
			p.setPos(pos);
			p.setRummyPlayer();
			p.getPresence().setRUMMYTID(_tid);
			p.getPresence().lastMove(Moves.RUMMY_SITIN);
			p.setRUMMYMoveId(0);

			addPresence(p);
			_cat.debug("Rummy21Server game - sit in req buf -- on table : "
							+ _tid);
			// send message to all players about the new player
			_lastMove = move_RUMMY_JOINED;
			_lastMovePos = pos;
			_lastMoveString = "Joined";

			p.rummyLeftTableTime = -1;
			p.rummyLastMoveTime = System.currentTimeMillis();

			int countPlayers = getCountActivePlayers();
			if (countPlayers == 1 && !_gameOngoing) {
				_winnerPos = -1;
			} else if (countPlayers == 2 && !_gameOngoing) {
				_winnerPos = -1;
				// fixing dealer process already on, then no need to start it
				// again
//				if (!fixingDealerOngoing) {
//					fixingDealerNextHand = true;
//					fixDealerFirst();
//				}
			} else {
				// not calling start game from here - that has to be done from
				// run method
//				fixingDealerNextHand = true;
				p.setRUMMYStatus(status_JOINED);
			}
			broadcastMessage(-1);
		}

		public void removePresence(RummyProfile p) {
			_players[p.pos()] = null;
		}

		public RummyProfile getPresence(int index) {
			return _players[index];
		}

		public boolean isActive(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_ACTIVE) > 0) {
				return true;
			}
			return false;
		}

		public boolean isRemoved(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_REMOVED) > 0) {
				return true;
			}
			return false;
		}

		public boolean isLeft(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_LEFT) > 0) {
				return true;
			}
			return false;
		}
//
//		public boolean isWinner(RummyProfile p) {
//			if ((p.getRUMMYStatus() & status_WINNER) > 0) {
//				return true;
//			}
//			return false;
//		}

		public boolean isFolded(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_FOLDED) > 0) {
				return true;
			}
			return false;
		}

		public boolean isDeclarerInvalid(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_DECLARE_INVALID) > 0) {
				return true;
			}
			return false;
		}

//		public boolean isBroke(RummyProfile p) {
//			if ((p.getRUMMYStatus() & status_BROKE) > 0) {
//				return true;
//			}
//			return false;
//		}

		public boolean isSittingOut(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_SITTINGOUT) > 0) {
				return true;
			}
			return false;
		}

		public boolean isJoined(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_JOINED) > 0) {
				return true;
			}
			return false;
		}

		public int getCountActivePlayers() {
//			_cat.debug("getcntactpl : ");
			int cnt = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null && !isLeft(_players[i])
						&&
						// !isFolded(_players[i]) &&
						!isRemoved(_players[i]) && !isSittingOut(_players[i])
						&& !isJoined(_players[i])){
					cnt++;
				}
			}

			return cnt;
		}

		public int getCntStatusActivePlayers() {
//			_cat.debug("getcntstatusactpl : ");
			int cnt = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null && isActive(_players[i]))
					cnt++;
			}

			return cnt;
		}

		public int getCountTotalPlayers() {
			int cnt = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null && !isLeft(_players[i])
						&& !isRemoved(_players[i]) ){
					cnt++;
				}
			}

			return cnt;
		}
		
		public int getCountSitInReqPlayers() {
			return sitinPlayers.size();
		}

		public int getRightOfPlayer(int pos) {
			int pos1 = decrPos(pos);
			if (pos1 == -1) {
				// seems like we just went around and still can't find next
				// player
				return -1;
			}
			// having ensured that this pos player is not null, let us
			// verify if status of this player allows game to proceed
			int countCalls = 0;
			while ((_players[pos1].getRUMMYStatus() & status_REMOVED) > 0
				//	|| (_players[pos1].getRUMMYStatus() & status_BROKE) > 0
					|| (_players[pos1].getRUMMYStatus() & status_SITTINGOUT) > 0
					|| (_players[pos1].getRUMMYStatus() & status_JOINED) > 0
					|| (_players[pos1].getRUMMYStatus() & status_LEFT) > 0
					) {
				_cat.debug("find next one : " + pos1 + " , stat: "
						+ _players[pos1].getRUMMYStatus() + " on table : "
						+ _tid);

				pos1 = decrPos(pos1);
				countCalls++;
				if (countCalls >= MAX_PLAYERS || pos1 == -1 || pos1 == pos) {
					// already this method has been called max times, nothing
					// comes out of it
					return -1;
				}
			}
			_cat.debug("next active pos : " + pos1 + " on table : "
					+ _tid);
			return pos1;
		}

		private int decrPos(int pos) {
			int origPos = pos;
			// get the next position
			if (pos == 0) {
				pos = MAX_PLAYERS - 1;
			} else
				pos--;

			while (_players[pos] == null) {
				pos--;
				if (pos < 0)
					pos = MAX_PLAYERS - 1;

				if (pos == origPos)
					return -1;
			}
			_cat.debug("decrpos player pos : " + pos + " on table : "
					+ _tid + ", from old pos : " + origPos);
			return pos;
		}

		public int getOnlyActivePos() {
			int pos1 = 0; // start from 0th index
			if (_players[pos1] == null) {
				pos1 = incrPos(pos1);
				if (pos1 == -1) {
					return -1;
				}
			}

			int countCalls = 0;
			while ((_players[pos1].getRUMMYStatus() & status_ACTIVE) <= 0) {
				_cat.debug("find next one : " + pos1 + " , stat: "
						+ _players[pos1].getRUMMYStatus() + " on table : "
						+ _tid);

				pos1 = incrPos(pos1);
				countCalls++;
				if (countCalls >= MAX_PLAYERS || pos1 == -1) {
					// already this method has been called max times, nothing
					// comes out of it
					return -1;
				}
			}
			_cat.debug("only active pos : " + pos1 + " on table : "
					+ _tid);
			return pos1;
		}

		public int getNextActivePos(int pos) {
			int pos1 = incrPos(pos);
			if (pos1 == -1) {
				// seems like we just went around and still can't find next
				// player
				return -1;
			}
			// having ensured that this pos player is not null, let us
			// verify if status of this player allows game to proceed
			int countCalls = 0;
			while ((_players[pos1].getRUMMYStatus() & status_FOLDED) > 0
					|| (_players[pos1].getRUMMYStatus() & status_REMOVED) > 0
//					|| (_players[pos1].getRUMMYStatus() & status_BROKE) > 0
					|| (_players[pos1].getRUMMYStatus() & status_SITTINGOUT) > 0
					|| (_players[pos1].getRUMMYStatus() & status_JOINED) > 0
					|| (_players[pos1].getRUMMYStatus() & status_DECLARE_INVALID) > 0
					|| (_players[pos1].getRUMMYStatus() & status_LEFT) > 0
					) {
				_cat.debug("find next one : " + pos1 + " , stat: "
						+ _players[pos1].getRUMMYStatus() + " on table : "
						+ _tid);

				pos1 = incrPos(pos1);
				countCalls++;
				if (countCalls >= MAX_PLAYERS || pos1 == -1 || pos1 == pos) {
					// already this method has been called max times, nothing
					// comes out of it
					return -1;
				}
			}
			_cat.debug("next active pos : " + pos1 + " on table : "
					+ _tid);
			return pos1;
		}

		private int incrPos(int pos) {
			int origPos = pos;
			// get the next position
			if (pos == MAX_PLAYERS - 1) {
				pos = 0;
			} else
				pos++;

			while (_players[pos] == null) {
				pos++;
				if (pos == MAX_PLAYERS)
					pos = 0;
				if (pos == origPos)
					return -1;
			}
			_cat.debug("incrpos player pos : " + pos + " on table : "
					+ _tid + ", from old pos : " + origPos);
			return pos;
		}

		private void handleMoveNoAction(int pos) {

			_cat.debug("handlemovenoaction - " + pos
					+ " with status : " + _players[pos].getRUMMYStatus());
			// if (isRemoved(_players[pos])) {
			// return;
			// }

			// always find the new dealer if player is leaving a table
//			fixingDealerNextHand = true;
			_lastMovePos = pos;
			_lastMoveString = "Left";
			
			chatOn = 0;
			chatMessage = _players[pos].name + " at pos : " + (pos + 1) + " left.";

			if (_nextMovePlayer != 111) {
				if (pos == _nextMovePlayer) {
					// same action as that of player leaving table
					handleTimedOut(true);
					return;
				}

				// not the player making move, but still mark it as folded, set
				// penalty
				if (_players[pos]._firstMoveDone)
					_players[pos].setRUMMYPoints(70);
				else {
					_players[pos].setRUMMYPoints(30);
					// player didn't even play one card, so put his cards in
					// fresh
					// pile
					addCardsToFreshPile(_players[pos]._allotedCards, 21);
				}

				_players[pos]._allotedCards = null;

				// if there are only 2 players and one of them di the hooky then
				// the
				// other player has to win
				// now if the leaving player is nextmoveose it is handled above
				// but
				// the opposite might be true too :-( - that is winking, not sad
				if (!checkGameOver()) {
					// game not over
					broadcastMessage(-1);
				}
			}
		}

		private void handleTimedOut(boolean left) {
			_cat.debug("handle time out");
			if (_players[_nextMovePlayer]._firstMoveDone)
				_players[_nextMovePlayer].setRUMMYPoints(70);
			else {
				_players[_nextMovePlayer].setRUMMYPoints(30);
				// player didn't even play one card, so put his cards in fresh
				// pile
				addCardsToFreshPile(_players[_nextMovePlayer]._allotedCards, 21);
			}

			_lastMovePos = _nextMovePlayer;
			if (!_lastMoveString.contains("Left"))
				_lastMoveString = "TimedOut";
				
			if (_players[_nextMovePlayer].isEligibleTimeBank()) {
				_players[_nextMovePlayer].setRUMMYMovesStr("&Folded");
				
				if (!left)
					_players[_nextMovePlayer].setRUMMYStatus(status_FOLDED);
				else
					_players[_nextMovePlayer].setRUMMYStatus(status_REMOVED);
			}
			else {
				//mark the player as left for he has now exhausted time bank
				_players[_nextMovePlayer].setRUMMYMovesStr("&TimedOut");
				_players[_nextMovePlayer].setRUMMYStatus(status_REMOVED);
				_players[_nextMovePlayer].setTimeBank(-1);
				_players[_nextMovePlayer].setUsingTimeBank(false);
			}
			
			_nextMoveExpTime = System.currentTimeMillis();
			
			_players[_nextMovePlayer].rummyLeftTableTime = System.currentTimeMillis();
			
			chatOn = 0;
			chatMessage = _players[_nextMovePlayer].name + " at pos : " + (_nextMovePlayer + 1) + " left.";

			if (!checkGameOver()) {
				// game not over
				_nextMovePlayer = getNextActivePos(_nextMovePlayer);
				_nextMovesAllowed = move_RUMMY_DECLARE | move_RUMMY_NEWCARD
						| move_RUMMY_FOLD;
				broadcastMessage(-1);
			}
		}

		// posWinner tells us which one won out or in - many players might have
		// bet on out/in
		public void declareGameOver(int posWinner) {
			_nextMoveExpTime = System.currentTimeMillis();
			_gameOngoing = false;
			_winnerPos = posWinner;
			String resString = "";
			StringBuffer sb = new StringBuffer();
			int countPotContenders = 0;
			
			//to hold max number of players
			final String[] names = new String[6];

			int totalWinPts = 0;
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null
						&& (isActive(_players[m]) || isLeft(_players[m]) || isRemoved(_players[m])
								|| isDeclarerInvalid(_players[m]) || isFolded(_players[m]))) {
					// compute the points of players still playing
					int pts = 0;
					if (m != _winnerPos) {
						if (isFolded(_players[m]) || isLeft(_players[m]) || isRemoved(_players[m])
								|| isDeclarerInvalid(_players[m]))
							pts = _players[m].getRUMMYPoints();
						else {
							idPlayerChecked = m;
							int[] retVal = checkValidCards2(
									_players[m]._allotedCards, 21, 0,
									_players[m].cardsStrFromClient);
							pts = retVal[0];
						}

						_players[m].setRUMMYPoints(pts);
						totalWinPts += pts;
					}
					names[countPotContenders++] = _players[m].name;
					
					_players[m].setRUMMYMovesStr("&Winner^" + _players[_winnerPos].getName());
				}
			}

			// now go thru the list of players again and set the proper points
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null
						&& (isActive(_players[m]) || isLeft(_players[m])
								|| isDeclarerInvalid(_players[m]) || isFolded(_players[m]))) {
					if (m != _winnerPos) {
						_players[m].setRUMMYPoints(-1
								* _players[m].getRUMMYPoints());
					} else {
						_players[m].setRUMMYPoints(totalWinPts);
					}
				}
			}
			// now set the value to be given and to be rcvd
			int numActivePlayersThisGame = 0;
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null
						&& ((isActive(_players[m]) || isDeclarerInvalid(_players[m])) && !isFolded(_players[m]))) {
					// folded players play no role here - they don't get
					// anything from their value cards
					numActivePlayersThisGame++;

					// now set the value of value cards for each player - not
					// for folded players
					int valRcvd = 0;
					if (_players[m].highValCutJoker > 0) {
						if (_players[m].highValCutJoker == 3)
							valRcvd += 50;
						else if (_players[m].highValCutJoker == 2)
							valRcvd += 30;
						else
							valRcvd += 10;
					}
					if (_players[m].highValDownJoker > 0) {
						if (_players[m].highValDownJoker == 3)
							valRcvd += 50;
						else if (_players[m].highValDownJoker == 2)
							valRcvd += 30;
						else
							valRcvd += 10;
					}
					if (_players[m].highValUpJoker > 0) {
						if (_players[m].highValUpJoker == 3)
							valRcvd += 50;
						else if (_players[m].highValUpJoker == 2)
							valRcvd += 30;
						else
							valRcvd += 10;
					}
					if (_players[m].marriageCount > 0) {
						if (_players[m].marriageCount == 3)
							valRcvd += 500;
						else if (_players[m].marriageCount == 2)
							valRcvd += 300;
						else
							valRcvd += 100;
					}
					_players[m].setRUMMYValCards(valRcvd);
					_cat.debug("value recvd : " + valRcvd + " for pos : " + m);

				}
			}

			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null
						&& ((isActive(_players[m]) || isDeclarerInvalid(_players[m])) && !isFolded(_players[m]))) {
					int toGive = 0;
					// another loop here - for each player we have to go thru
					// all other players to find out how much to give
					for (int k = 0; k < MAX_PLAYERS; k++) {
						if (_players[k] != null
								&& ((isActive(_players[k]) || isDeclarerInvalid(_players[k])) && !isFolded(_players[k]))) {
							if (m != k) {
								// avoiding the pitfall of checking one's own
								// value cards
								toGive += _players[k].getRUMMYValCards();
							}
						}
					}// internal for loop ends here

					_cat.debug("val to be given : " + toGive + " for pos " + m);

					_players[m].setRUMMYValGiven(toGive);
				}
			}

			// now we can compute the total win points and all
			double totalRake = 0;
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null
						&& (isActive(_players[m]) || isLeft(_players[m])
								|| isDeclarerInvalid(_players[m]) || isFolded(_players[m]))) {
					int pts = _players[m].getRUMMYPoints();
					pts -= _players[m].getRUMMYValGiven();
					pts += _players[m].getRUMMYValCards()
							* (numActivePlayersThisGame - 1);

					double amtWon = 0;

					double ptsToRem = pts;

					if (pts < 0) {
						pts *= -1;

						// max that a player can lose is capped at 240
						if (pts > 240)
							pts = 240;
						
						_players[m].getPresence().currentRoundBet(
								pts * POINTSPERRUPEE);
						_players[m].setRUMMYWinAmt(0);
						amtWon = -1 * pts * POINTSPERRUPEE;
						_players[m].setRUMMYMovesStr("&Penalty^" + pts);
						_amtWonString += m + "'" + (pts * POINTSPERRUPEE * -1) + "|";
					} else {
						double winamt = pts * POINTSPERRUPEE;
						_players[m]._rake = winamt * RAKE_PERCENTAGE / 100;
						totalRake += _players[m]._rake;
						winamt -= _players[m]._rake;
						_players[m].setRUMMYWinAmt(winamt);
						_players[m].getPresence().addToWin(winamt);
						amtWon = winamt;
						_players[m].setRUMMYMovesStr("&WinPoints^" + pts);
						_amtWonString += _winnerPos + "'" + winamt;
					}

					// hack to make amtWon be reduced to 2 digits after decimal
					// point
					int amtInt = (int) (amtWon * 100);
					amtWon = ((double) amtInt) / 100;

					sb.append(m + "`" + _players[m].name + "`"
							+ _players[m].valueCardString + "`"
							+ _players[m].getRUMMYValCards()
							* (numActivePlayersThisGame - 1) + "`"
							+ _players[m].getRUMMYValGiven() + "`"
							+ _players[m].getRUMMYPoints() + "`" + ptsToRem
							+ "`" + amtWon + "'");
				}
			}

			sb.deleteCharAt(sb.length() - 1);

			_cat.debug("printing result : " + sb.toString());

			// hack - comment later
			_cat.debug("winners win amt computd");
			_cat.debug("total players in hand : " + countPotContenders);
			
			_rake = totalRake;
			dbpotcontendors = countPotContenders;
			dbtotalpoints = totalWinPts;
			
			resString = countPotContenders + ":" + sb.toString();
			_cat.debug("resString : " + resString);
			
			//so that sit out and sit in can be shown at the earliest
			handleSitoutSitin();
			
			_winnerString = resString;
			broadcastMessage(posWinner);
			
			new Thread(){
				public void run(){
					// loop thru the players, find the participating players
					// insert their entries in t_player_per_grs
					GameRunSession grs = new GameRunSession(_gid, rummygrid,GameType.Rummy21);//16384 is for rummy21
//							_type.intVal());
					grs.setEndTime(new Date());
					grs.setStartTime(_gameStartTime.getTime());

					double rake[];
					rake = Utils.integralDivide(_rake, dbpotcontendors);

					// ************STARTING BATCH
					int j = -1;
					try {
						Statement grs_stat = grs.startBatch();
						for (int i = 0; i < MAX_PLAYERS; i++) {
							// _playersInHand
							if (_players[i] == null)
								continue;

							RummyProfile pr = _players[i];
							j++;

							GamePlayer gp = (GamePlayer) pr.getPresence().player();
							grs.setDisplayName(gp.name());
							grs.setPosition(pr.pos());
							grs.setPot(dbtotalpoints * POINTSPERRUPEE);
							double betamnt = pr.getPresence().currentRoundBet();
							grs.setStartWorth(pr.getGameStartWorth());

							grs.setWinAmount(0);
							// for winner only these 2 lines
							if (_players[i].getRUMMYWinAmt() > 0) {
								// win amt will be actual win amt after accounting for
								// bet amt
								grs.setWinAmount(_players[i].getRUMMYWinAmt());
								grs.setEndWorth(pr.getGameStartWorth()
										+ _players[i].getRUMMYWinAmt() - betamnt);
							} else {
								grs.setEndWorth(pr.getGameStartWorth() - betamnt);
							}
							grs.setBetAmt(betamnt);
							// now for all
							grs.setSessionId(gp.session());
							grs.setRake(rake[j]);

							grs.setMoveDet(pr.getRUMMYMovesStr());
							grs.save(grs_stat);

							// now set the player's start worth
							_players[i].setGameStartWorth(grs.getEndWorth());
						}
						// COMMITTING BATCH
						grs.commitBatch(grs_stat);
						_cat.debug("grs committed...");
						// TBD- login session have to be updated with win and bet
					} catch (Exception ex) {
						_cat.debug("Exception - " + ex.getMessage());
					}
				}
			}.start();
			
			//for t_user_eap, this is the only place
			new Thread(){
				public void run(){
					double rake[];
					rake = Utils.integralDivide(_rake, dbpotcontendors);
					
					for (int m = 0; m < dbpotcontendors; m++){
						if (names[m] == null)
							continue;
						DBEAP dbeap = new DBEAP(names[m], rake[m]);
						try {
							dbeap.setUserEAP();
						} catch (DBException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}
			}.start();
			
		}

		void addCardsToFreshPile(Card[] crs, int index) {
			if (crs == null)
				return;

			for (int i = 0; i < index; i++) {
				if (crs[i].getIndex() > -1) {
					_deck.clear(crs[i].getIndex());
				}
			}
		}

		boolean checkGameOver() {
			int cntActPlyrs = getCntStatusActivePlayers();
			if (cntActPlyrs < 2) {
				// game can't proceed, the lone ranger is the winner
				if (cntActPlyrs >= 1) {
					int pos = getOnlyActivePos();
					if (pos != -1 && _gameOngoing) {
						if (_players[pos].getUsingTimeBank()){
							_players[pos].setTimeBank(_players[pos].getTimeBankExpTime() - System.currentTimeMillis());
							_players[pos].setUsingTimeBank(false);
							_cat.debug("ha! player with tb now shut : " + _players[pos].getUsingTimeBank() + " for pos " + pos);
						}
						
						declareGameOver(pos);
					} else {
						// for some reaons either game is not on or pos is -1
						_gameOngoing = false;
					}
				} else {
					// less than 1 player on table - sad state of affairs
					_gameOngoing = false;
				}

				return true;
			} else {
				return false;
			}
		}

		int adjustNextToDealer(RummyProfile rp) {
			_cat.debug("trying to adjust next to dealer for pos : "
					+ rp.pos());
			// if a pos is availabe
			int pos = findPosWhoseNextIsFreePos();
			_cat.debug("found a free pos : " + pos);
			if (pos != -1) {
				// if this player is the dealer, balle balle - no need to shift
				// any player
				// if this is some other player, then we have to shift all the
				// players starting from dealer left to max valid pos
				// so that we make space for new player -- this will be done in
				// adjustNexttoDealer
				if (_dealer == pos) {
					_players[pos + 1] = rp;
					rp._pos = pos + 1;
					// shift the dealer after game is over
					return rp.pos();
				}

				// the dealer is somewhere in the middle of players, start
				// shifting the players above the dealer
				// for (int i = pos; i > _dealer; i--) {
				// _players[i + 1] = _players[i];
				// _players[i+1]._pos = i+1;
				// _players[i] = null;
				//
				// //shift the _nextmovePos and _rummyPlayer tag
				// if (_rummyPlayer == i)
				// _rummyPlayer = i + 1;
				// if (_nextMovePlayer == i)
				// _nextMovePlayer = i + 1;
				// }
				_players[pos + 1] = rp;
				_players[pos + 1]._pos = pos + 1;
				return rp.pos();
			}

			return -1;
		}

		int findPosWhoseNextIsFreePos() {
			int retPos = -1;
			// now find the max pos of player whose next left is empty
			// from this method we only send the max valid pos of player whose
			// next left is empty
			// error case : if 1st pos is empty, check it and return right away
			// if (_players[0] == null) {
			// return -1;
			// }

			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (i + 1 >= MAX_PLAYERS) {
					if (_players[5] != null && _players[0] == null)
						return 5;
				}

				if (_players[i] != null && _players[i + 1] == null)
					return i;
			}

			return retPos;
		}

		// Vikas
		// ****************************************************************************************************
		int isThisCardJokerRank(int checkCard) {
			if (checkCard == -1)
				return 2;
			int[] dummy = new int[3];
			dummy[0] = _rummyCardJoker.getRank();
			if (_rummyCardJoker.getRank() == Card.TWO)
				dummy[1] = 12;
			else
				dummy[1] = dummy[0] - 1;

			if (_rummyCardJoker.getRank() == Card.ACE)
				dummy[2] = 0;
			else
				dummy[2] = dummy[0] + 1;

			if (checkCard == dummy[0] || checkCard == dummy[1]
					|| checkCard == dummy[2])
				return 1;

			return 0;
		}

		boolean determineIfJoker(Card crd) {
			if (crd.getIndex() >= 156)
				return true;

			int[] dummy = new int[3];
			dummy[0] = _rummyCardJoker.getRank();
			if (_rummyCardJoker.getRank() == Card.TWO)
				dummy[1] = _rummyCardJoker.getIndex() + 12;
			else
				dummy[1] = _rummyCardJoker.getIndex() - 1;

			if (_rummyCardJoker.getRank() == Card.ACE)
				dummy[2] = _rummyCardJoker.getIndex() - 12;
			else
				dummy[2] = _rummyCardJoker.getIndex() + 1;

			// first check the cut joker
			if (crd.getRank() == dummy[0])
				return true;

			// not so, then check the lower and upper joker - remember only
			// index will do here not the rank
			if (crd.getIndex() == dummy[1] || crd.getIndex() == dummy[2])
				return true;

			return false;
		}

		boolean CheckIfSets(String[] toks2) {
			int jokerCount = 0, playerCount = 0;
			Card[] playerCards = new Card[4];

			for (int i = 0; i < toks2.length; i++) {
				Card cr = new Card(toks2[i]);
				if (determineIfJoker(cr))
					jokerCount++;
				else {
					playerCards[playerCount++] = cr;
				}
			}

			if (playerCount == 1 && jokerCount >= 2) {
				// can put it as impure run or set. goign by set logic
				return true;
			} else {
				if (jokerCount == 2) {
					// 2 jokers and 2 cards can make a 4 card set - if it were 1
					// card with 2 jokers - case handled above
					if (playerCount == 2) {
						// other two have to be pair else it is an epic fail
						if (playerCards[0].getRank() == playerCards[1]
								.getRank()
								&& playerCards[0].getIndex() != playerCards[1]
										.getIndex()) {
							// it is a set - don't want dublee here
							return true;
						}
					}
				} else if (jokerCount == 1) {
					if (playerCount == 2) {
						// other two have to be pair else it is an epic fail
						if (playerCards[0].getRank() == playerCards[1]
								.getRank()
								&& playerCards[0].getIndex() != playerCards[1]
										.getIndex()) {
							// it is a set - don't want dublee here
							return true;
						}
					} else if (playerCount == 3) {// it is a 4 card set with one
													// joker
						if (playerCards[0].getRank() == playerCards[1]
								.getRank()
								&& playerCards[0].getIndex() != playerCards[1]
										.getIndex()
								&& playerCards[0].getRank() == playerCards[2]
										.getRank()
								&& playerCards[0].getIndex() != playerCards[2]
										.getIndex()) {
							// it is a set - don't want dublee here
							return true;
						}
					}
				} else if (jokerCount == 0) {
					// all three cards have to have same rank now
					if (playerCount == 3) {
						if (playerCards[0].getRank() == playerCards[1]
								.getRank()
								&& playerCards[0].getIndex() != playerCards[1]
										.getIndex()
								&& playerCards[0].getRank() == playerCards[2]
										.getRank()
								&& playerCards[0].getIndex() != playerCards[2]
										.getIndex()) {
							// it is a set - don't want dublee here
							return true;
						}
					} else if (playerCount == 4) {
						// 4 card pure set
						if (playerCards[0].getRank() == playerCards[1]
								.getRank()
								&& playerCards[0].getIndex() != playerCards[1]
										.getIndex()
								&& playerCards[0].getRank() == playerCards[2]
										.getRank()
								&& playerCards[0].getIndex() != playerCards[2]
										.getIndex()
								&& playerCards[0].getRank() == playerCards[3]
										.getRank()
								&& playerCards[0].getIndex() != playerCards[3]
										.getIndex()) {
							// it is a set - don't want dublee here
							return true;
						}
					}
				}
			}
			return false;
		}

		boolean CheckPureRuns(String[] toks2) {
			int playerCount = 0;
			Card[] playerCards = new Card[toks2.length];

			for (int i = 0; i < toks2.length; i++) {
				Card cr = new Card(toks2[i]);
				playerCards[playerCount++] = cr;
			}

			// having jokers is not a criteria to reject the pure run. perhaps
			// joker is being used as is what is

			ArrayList<Integer> cardsArr = new ArrayList();
			for (int index = 0; index < toks2.length; index++) {
				cardsArr.add(new Card(toks2[index]).getIndex());
			}
			Collections.sort(cardsArr);

			int suitRun = -1;
			int[] cardTwo = new int[toks2.length];
			for (int i = 0; i < toks2.length; i++) {
				// these cards have to be of same suit or else no run is
				// possible - brouhaahaha
				cardTwo[i] = cardsArr.get(i);
				if (suitRun == -1)
					suitRun = cardTwo[i] / 13;
				else {
					int newsuit = cardTwo[i] / 13;
					if (suitRun != newsuit)
						return false;// cards not of same suit, nuff said
				}
			}

			// the cardTwo array has sorted cards - if this is a pure run, the
			// cards have to be in ascending order
			boolean runpure = true;
			int beginIndex = cardTwo[0];
			for (int i = 1; i < toks2.length; i++) {
				if (beginIndex + 1 != cardTwo[i]) {
					runpure = false;
					break;
				}
				beginIndex = cardTwo[i];
			}

			if (!runpure) {
				// check for the case of Ace, Two, Three et al. for these cases
				// as Ace is at the end the condition will fail
				if (cardTwo[toks2.length - 1] % 13 == Card.ACE) {
					runpure = true;
					beginIndex = -1; // so that when you add 1 you get 0 which
										// is Card.TWO
					for (int i = 0; i < toks2.length - 1; i++) {
						if (beginIndex + 1 != cardTwo[i] % 13) {
							runpure = false;
							break;
						}
						beginIndex = cardTwo[i] % 13;
					}
				}
			}

			return runpure;
		}

		boolean CheckImPureRuns(String[] toks2) {
			int playerCount = 0, jokerCount = 0;
			Card[] playerCards = new Card[toks2.length];

			for (int i = 0; i < toks2.length; i++) {
				Card cr = new Card(toks2[i]);
				if (determineIfJoker(cr))
					jokerCount++;
				else {
					playerCards[playerCount++] = cr;
				}
			}

			if (playerCount == 1 && jokerCount >= 2)
				return true;
			if (playerCount == 0)
				return false;

			// there will be jokers and they will be completing the run.
			// find the total gap that needs to be covered by jokers. then check
			// if you have that many jokers in the group
			// if so, then balle balle. if not, then what yaar, try next one!

			ArrayList<Integer> cardsArr = new ArrayList();
			for (int index = 0; index < playerCount; index++) {
				cardsArr.add(playerCards[index].getIndex());
			}
			Collections.sort(cardsArr);

			int suitRun = -1;
			int[] cardTwo = new int[playerCount];
			for (int i = 0; i < playerCount; i++) {
				// these cards have to be of same suit or else no run is
				// possible - brouhaahaha
				cardTwo[i] = cardsArr.get(i);
				if (suitRun == -1)
					suitRun = cardTwo[i] / 13;
				else {
					int newsuit = cardTwo[i] / 13;
					if (suitRun != newsuit)
						return false;// cards not of same suit, nuff said
				}
			}

			// the cardTwo array has sorted cards - if this is a pure run, the
			// cards have to be in ascending order
			int jokerNeeded = 0;
			int beginIndex = cardTwo[0];
			for (int i = 1; i < playerCount; i++) {
				if (beginIndex + 1 != cardTwo[i]) {
					// we need joker or jokers to move from beginIndex to
					// cardTwo[i]
					int value = cardTwo[i] - beginIndex - 1;
					// this is a very special case - suppose there are 2 of 10S
					// and
					// we have a joker
					// our logic will give value as -1 and that is less than 1
					// so it
					// will treat it as a valid sequence
					// not now.
					if (value < 0)
						return false;

					jokerNeeded += value;
				}
				beginIndex = cardTwo[i];
			}

			if (jokerNeeded <= jokerCount)
				return true;
			else {
				// there is a special case of Ace where it can be used with Two
				// and Three and others to make a run
				if (cardTwo[playerCount - 1] % 13 == Card.ACE) {
					// last card is an Ace - go for it
					jokerNeeded = 0;
					beginIndex = -1;
					for (int i = 0; i < playerCount - 1; i++) {
						if (beginIndex + 1 != cardTwo[i] % 13) {
							// we need joker or jokers to move from beginIndex
							// to cardTWo[i]
							int indCard = cardTwo[i] % 13;
							int indCard2 = beginIndex + 1;
							int val = indCard - indCard2;
							if (val < 0)
								return false;

							jokerNeeded += val;
						}
						beginIndex = cardTwo[i] % 13;
					}

					if (jokerNeeded <= jokerCount)
						return true;
					else
						return false;
				}

				return false;
			}
		}

		String countRunsAsArranged(String cardsstr, int challenger) {
			int pureRuns = 0, impureRuns = 0, numSets = 0, tanalaCount = 0, dubleeCount = 0;
			String pureRunGroup = "";
			String allRunsSetsGroup = "";

			String[] toks1 = cardsstr.split("\\|");
			for (int i = 0; i < toks1.length; i++) {
				boolean somethingfound = false;
				String[] toks2 = toks1[i].split("\\`");
				// got the cards of one group
				// there can be these cases - a pure run, an impure run, a pure
				// set, an impure set, a tanala, a dublee
				if (toks2.length == 2) {
					// could be a dublee. if not, then useless
					if (toks2[0].compareToIgnoreCase(toks2[1]) == 0)
						dubleeCount++;
				}
				if (toks2.length == 3) {
					// could be a tanala
					if (toks2[0].compareToIgnoreCase(toks2[1]) == 0
							&& toks2[0].compareToIgnoreCase(toks2[2]) == 0) {
						tanalaCount++;
						pureRunGroup += i + "'";
						somethingfound = true;
					}
					if (!somethingfound) {
						// check if these are 3 printed jokers
						boolean flag = true;
						for (int ij = 0; ij < 3; ij++) {
							int val = new Card(toks2[ij]).getIndex();
							if (val < 161) {
								flag = false;
								break;
							}
						}
						if (flag) {
							// we have a set of 3 printed jokers, mubarak ho
							tanalaCount++;
							pureRunGroup += i + "'";
							somethingfound = true;
						}
					}
				}

				if (toks2.length >= 3 && !somethingfound) {
					// check for run
					if (CheckPureRuns(toks2)) {
						pureRuns++;
						pureRunGroup += i + "'";
						somethingfound = true;
					} else if (CheckImPureRuns(toks2)) {
						impureRuns++;
						allRunsSetsGroup += i + "'";
						somethingfound = true;
					}

					if (!somethingfound
							&& (toks2.length == 3 || toks2.length == 4)) {
						// could be a set -. take care. there could be a joker
						// here
						if (CheckIfSets(toks2)) {
							numSets++;
							allRunsSetsGroup += i + "'";
							somethingfound = true;
						}
					}
				}
			}

			// we have gone thru cards
			if (tanalaCount + pureRuns < 3) {
				// just check if player was going for dublees, moron
				if (dubleeCount >= (7 + challenger)) {
					return "dublee";
				}
				// oooh, hand not valid, ooh, run like chicken
				if (!pureRunGroup.isEmpty()) {
					pureRunGroup = pureRunGroup.substring(0,
							pureRunGroup.length() - 1);
				}
				return pureRunGroup;
			} else {
				// valid hand - send all runs and sets to be removed
				pureRunGroup += allRunsSetsGroup;
				if (!pureRunGroup.isEmpty()) {
					pureRunGroup = pureRunGroup.substring(0,
							pureRunGroup.length() - 1);
				}
				return pureRunGroup;
			}
		}

		Card[] removeAllCardsOfRun2(String cards, String runs) {
			String[] toks1 = cards.split("\\|");
			// toks1 has the groups now. the indices to be removed are there in
			// runs
			String[] runToks1 = runs.split("\\'");
			int[] runTks = new int[runToks1.length];
			for (int i = 0; i < runTks.length; i++) {
				runTks[i] = Integer.parseInt(runToks1[i]);
			}

			int lenRetCards = 0;
			String retCards = "";
			for (int i = 0; i < toks1.length; i++) {
				// go thru the orig array of groups. find the length of cards
				// needed to be penalized
				boolean found = false;
				for (int j = 0; j < runTks.length; j++) {
					if (i == runTks[j]) {
						found = true;
						break;
					}
				}

				if (!found) {
					String[] toks2 = toks1[i].split("\\`");
					lenRetCards += toks2.length;
					retCards += toks1[i] + "'";
				}
			}
			if (!retCards.isEmpty()) {
				retCards = retCards.substring(0, retCards.length() - 1);
			}

			// now get the returning cards for penalty
			if (lenRetCards > 0) {
				Card[] toRetCards = new Card[lenRetCards];
				int indexRetCards = 0;

				String[] tokis = retCards.split("\\'");
				for (int m = 0; m < tokis.length; m++) {
					String[] toks2 = tokis[m].split("\\`");
					for (int k = 0; k < toks2.length; k++) {
						toRetCards[indexRetCards++] = new Card(toks2[k]);
					}
				}

				if (indexRetCards > 0)
					return toRetCards;
				else
					return null;
			}

			return null;
		}

		int[] checkValidCards2(Card[] cards, int cardsLen, int challenger,
				String cardsStrFrmClnt) {
			
			//value cards computation : doing it here because we have to do it for all players
			//once they hand in their final cards.
			int[] tempCards = new int[cardsLen];
			for (int i = 0; i < cardsLen; i++) {
				tempCards[i] = cards[i].getIndex();
				System.out.print("-?-" + tempCards[i]);
			}
			String[] resVa = findMarriage2(tempCards);
			
			int[] resTemp = new int[3];// 0 holds the points, 1 holds 1 if 1st
										// run is valid else 0, 2 holds 1 if 2nd
										// run is valid else 0

			// if the cards string from player is empty, most likely he/she
			// didn't send it - apply penalty of 120 and let it be
			if (cardsStrFrmClnt.isEmpty()) {
				resTemp[0] = 120;
				resTemp[1] = 0;
				resTemp[2] = 0;
				return resTemp;
			}

			// clear out all global variables
			bitsetJoker = new BitSet();
			_players[idPlayerChecked].dubleeCount = 0;
			_players[idPlayerChecked].jokerCount = 0;

			String[] indicesCardsInRun = new String[13];
			for (int i = 0; i < 13; i++) {
				indicesCardsInRun[i] = "";
			}
			finalResultRuns = "";
			_players[idPlayerChecked].tanalaCount = 0;

			int[] removedCardsDupli = new int[10];// at max 10 dublees can be
													// there in 21 cards
			int indexDupli = 0;

			int countJokers = 0;
			for (int i = 0; i < cardsLen; i++) {
				// _cat.debug("playing with cards : " +
				// cards[i].toString());
				if (determineIfJoker(cards[i]))
					countJokers++;
			}

			int[] cardStatus = new int[52];
			for (int i = 0; i < 52; i++) {
				cardStatus[i] = 0;
			}

			_players[idPlayerChecked].jokerCount = countJokers;

			// now that we know how many jokers are there, set them
			bitsetJoker.clear();
			int index = 0;
			for (int i = 0; i < cardsLen; i++) {
				if (determineIfJoker(cards[i])) {
					_cat.debug("joker found : " + cards[i].toString());
					if (bitsetJoker.get(cards[i].getIndex() + 52)) {
						bitsetJoker.set(cards[i].getIndex() + 164);
						bitsetJoker.clear(cards[i].getIndex() + 52);
						removedCardsDupli[indexDupli++] = 165;
						cards[i] = new Card(164);
					} else if (bitsetJoker.get(cards[i].getIndex()))
						bitsetJoker.set(cards[i].getIndex() + 52);
					else
						bitsetJoker.set(cards[i].getIndex());
				}

				if (cards[i].getIndex() < 156) {
					int tempI = cards[i].getIndex();
					cardStatus[tempI]++;
					if (cardStatus[tempI] >= 2)
						_players[idPlayerChecked].dubleeCount++;
				}
			}
			
			//11th of Sep 2017 - tanala was being counted as dublee
			//not allowed anymore
			// //very first step, check for tunnela. if present, remove those
			// blasted cards
			int[] restVal = findTunnela2(tempCards);
			// check if tanala count >= 3, if so game over, return
			if (_players[idPlayerChecked].tanalaCount >= 3) {
				_cat.debug("hurrah ! tanal count to beat all tanala counts : "
								+ _players[idPlayerChecked].tanalaCount);
				resTemp[0] = 0;
				resTemp[1] = 0;
				resTemp[2] = 1;
				return resTemp;
			}
						
			_players[idPlayerChecked].dubleeCount -= _players[idPlayerChecked].tanalaCount;

			// special case of 8 jokers and 8 dublees go here - if so no need to
			// do any thing funny below
			if (challenger == 1) {
				if (_players[idPlayerChecked].jokerCount >= 8
						|| _players[idPlayerChecked].dubleeCount >= 8
						|| (_players[idPlayerChecked].dubleeCount == 7 && _players[idPlayerChecked].pjcount >= 2)) {
					_cat.debug("hurrah ! joker count : "
							+ _players[idPlayerChecked].jokerCount
							+ " , dublee : "
							+ _players[idPlayerChecked].dubleeCount);
					resTemp[0] = 0;
					resTemp[1] = 0;
					resTemp[2] = 1;
					return resTemp;
				}
			} else {
				if (_players[idPlayerChecked].jokerCount >= 7
						|| _players[idPlayerChecked].dubleeCount >= 6
						|| (_players[idPlayerChecked].dubleeCount == 5 && _players[idPlayerChecked].pjcount >= 2)) {
					_cat.debug("hurrah ! joker count : "
							+ _players[idPlayerChecked].jokerCount
							+ " , dublee : "
							+ _players[idPlayerChecked].dubleeCount);
					resTemp[0] = 2;
					resTemp[1] = 0;
					resTemp[2] = 1;
					return resTemp;
				}
			}

			// special case for rummy 21 - even printed jokers can make a tanala
			// if we have 3 of them
			if (_players[idPlayerChecked].tanalaCount == 2
					&& _players[idPlayerChecked].pjcount == 3) {
				_cat.debug("haha : tanala + printed jokers make a great couple");
				resTemp[0] = 0;
				resTemp[1] = 0;
				resTemp[2] = 1;
				return resTemp;
			}

			// clear the runs now - we are going to go exactly as per the player
			// arrangement of cards
			for (int i = 0; i < 13; i++) {
				indicesCardsInRun[i] = "";
			}
			// we couldn't do anything with tunnela - let us search how many
			// runs are there
			int[] countPureRuns = new int[2];// 0th index will have number of
												// pure runs. 1st index will
												// have other runs.
			countPureRuns[0] = 0;
			countPureRuns[1] = 0;

			String restrVal = countRunsAsArranged(cardsStrFrmClnt, challenger);
			if (!restrVal.isEmpty()) {
				if (restrVal.compareToIgnoreCase("dublee") == 0) {
					resTemp[0] = 0;
					resTemp[1] = 0;
					resTemp[2] = 1;
					return resTemp;
				} else {
					String[] toks1 = restrVal.split("\\'");
					if (toks1.length < 3) {
						countPureRuns[0] = toks1.length;
					} else {
						// first sign of a valid hand - all 3 pure runs are
						// there
						countPureRuns[0] = 3;
						countPureRuns[1] = toks1.length - 3;
					}
				}
			}

			if (countPureRuns[0] > 0) {
				// we do have 1st life.
				if (countPureRuns[0] >= 3) {
					_cat.debug("jeeo, lives are valid");

					Card[] cardsPenal = removeAllCardsOfRun2(cardsStrFrmClnt,
							restrVal);
					resTemp[0] = computePoints2(cardsPenal);
					resTemp[1] = countPureRuns[0];
					if (resTemp[0] > 0) {
						resTemp[2] = 0;
					} else {
						resTemp[2] = 1;// it is a valid hand, what more do you want you moron
						if (challenger == 1)
							resTemp[0] = 0;
						else
							resTemp[0] = 2; //penalty of 2 for not declaring on time
					}

					return resTemp;
				} else {
					// we have 1st life but no second life - remove cards of
					// pure runs
					_cat.debug("we don't have 3 pure runs but we do have : "
									+ countPureRuns);
					Card[] cardsPenal = removeAllCardsOfRun2(cardsStrFrmClnt,
							restrVal);

					resTemp[0] = computePoints2(cardsPenal);
					resTemp[1] = countPureRuns[0];
					resTemp[2] = 0;
					return resTemp;
				}
			} else {
				// no first life - penalty on all cards
				resTemp = new int[3];
				resTemp[0] = computePoints2(cards);
				resTemp[1] = 0;
				resTemp[2] = 0;
				return resTemp;
			}
		}

		int[] findTunnela2(int[] cards) {
			// for tanala
			int[] resVal = new int[7];// maximum 4 tunnelas can be there in 21
										// cards set
			for (int i = 0; i < 7; i++)
				resVal[i] = -1;

			int indexVal = 0;
			ArrayList<Integer> cardsArr = new ArrayList();
			for (int index = 0; index < cards.length; index++) {
				cardsArr.add(cards[index]);
			}
			Collections.sort(cardsArr);

			int[] cardOne = new int[cards.length];
			for (int i = 0; i < cards.length; i++) {
				cardOne[i] = cardsArr.get(i);
			}

			for (int i = 0; i < cards.length; i++) {
				// break condition
				if (i + 1 >= cards.length) {
					break;
				} else if (i + 2 >= cards.length) {
					break;
				}

				if (cardOne[i] - cardOne[i + 1] == 0
						&& cardOne[i + 1] - cardOne[i + 2] == 0) {
					_cat.debug("tunnela found" + cardOne[i] + " , "
							+ cardOne[i + 1] + " , " + cardOne[i + 2]);

					String str = cardOne[i] + "`" + (cardOne[i + 1] + 52) + "`"
							+ (cardOne[i + 2] + 104);

					_players[idPlayerChecked].tanalaCount++;

					resVal[indexVal++] = cardOne[i];

					i += 2; // it will be incremented by 1 when loop ends
					continue;
				}
			}
			return resVal;
		}

		String[] findMarriage2(int[] cards) {
			// for marriages, just count the number of times the paplu, tiplu
			// and jhiplu are there in the 21 cards - the min number is the
			// number of marriages
			// now i need to also ensure that i remove the cards marked as
			// marriages. this might be a problem for tanala
			// so will do it after finding tunnala -
			String[] resVal = new String[4];// maximum 4 marriages can be there
											// in 21 cards set
			for (int i = 0; i < 4; i++)
				resVal[i] = "";

			int indexVal = 0;
			ArrayList<Integer> cardsArr = new ArrayList();
			for (int index = 0; index < cards.length; index++) {
				cardsArr.add(cards[index]);
			}
			Collections.sort(cardsArr);

			int[] cardOne = new int[cards.length];
			for (int i = 0; i < cards.length; i++) {
				cardOne[i] = cardsArr.get(i);
			}

			int[] dummy = new int[3];
			dummy[0] = _rummyCardJoker.getIndex();
			if (_rummyCardJoker.getRank() == Card.TWO)
				dummy[1] = _rummyCardJoker.getIndex() + 12;
			else
				dummy[1] = _rummyCardJoker.getIndex() - 1;

			if (_rummyCardJoker.getRank() == Card.ACE)
				dummy[2] = _rummyCardJoker.getIndex() - 12;
			else
				dummy[2] = _rummyCardJoker.getIndex() + 1;

			_cat.debug("dummy 0 : " + dummy[0] + " , 1 : " + dummy[1]
					+ " , 2 : " + dummy[2]);

			for (int i = 0; i < cards.length; i++) {
				// for each card, check if it is any one kind of joker
				if (cardOne[i] > 156)
					_players[idPlayerChecked].pjcount++;
				if (cardOne[i] == dummy[0])
					_players[idPlayerChecked].highValCutJoker++;
				if (cardOne[i] == dummy[1])
					_players[idPlayerChecked].highValDownJoker++;
				if (cardOne[i] == dummy[2])
					_players[idPlayerChecked].highValUpJoker++;
			}

			int min = _players[idPlayerChecked].highValDownJoker;
			if (min > _players[idPlayerChecked].highValCutJoker)
				min = _players[idPlayerChecked].highValCutJoker;
			if (min > _players[idPlayerChecked].highValUpJoker)
				min = _players[idPlayerChecked].highValUpJoker;

			_players[idPlayerChecked].marriageCount = min;

			_cat.debug("marraige : "
					+ _players[idPlayerChecked].marriageCount + " , cut : "
					+ _players[idPlayerChecked].highValCutJoker + ", up : "
					+ _players[idPlayerChecked].highValUpJoker + ", down : "
					+ _players[idPlayerChecked].highValDownJoker);
			// now remove the card count used in marriages from individual high
			// val card counts
			_players[idPlayerChecked].highValCutJoker -= min;
			_players[idPlayerChecked].highValUpJoker -= min;
			_players[idPlayerChecked].highValDownJoker -= min;

			// now we have to find all these marriages and make them as runs so
			// that no one touches these cards
			if (_players[idPlayerChecked].marriageCount > 0) {
				int cnt = _players[idPlayerChecked].marriageCount;
				int val = 0;
				while (cnt > 0) {
					String str = (dummy[1] + val) + "`" + (dummy[0] + val)
							+ "`" + (dummy[2] + val);

					resVal[indexVal++] = str;

					// this is a good place to set the cards being used
					String[] toks = str.split("\\`");
					for (int ik = 0; ik < toks.length; ik++) {
						int tempI = Integer.parseInt(toks[ik]);
						while (tempI >= 52)
							tempI -= 52;
					}
					cnt--;
					val += 52;
				}
			}

			// create the value card string
			// _players[idPlayerChecked].valueCardString
			int tempIndex = _players[idPlayerChecked].marriageCount;
			if (tempIndex > 0) {
				_players[idPlayerChecked].valueCardString += tempIndex + "^"
						+ _rummyDownJoker + "-" + _rummyCardJoker + "-"
						+ _rummyUpJoker + "|";
			}
			tempIndex = _players[idPlayerChecked].highValCutJoker;
			if (tempIndex > 0) {
				_players[idPlayerChecked].valueCardString += tempIndex + "^"
						+ _rummyCardJoker + "|";
			}

			tempIndex = _players[idPlayerChecked].highValUpJoker;
			if (tempIndex > 0) {
				_players[idPlayerChecked].valueCardString += tempIndex + "^"
						+ _rummyUpJoker + "|";
			}

			tempIndex = _players[idPlayerChecked].highValDownJoker;
			if (tempIndex > 0) {
				_players[idPlayerChecked].valueCardString += tempIndex + "^"
						+ _rummyDownJoker + "|";
			}

			if (!_players[idPlayerChecked].valueCardString.isEmpty()) {
				_players[idPlayerChecked].valueCardString = _players[idPlayerChecked].valueCardString
						.substring(0, _players[idPlayerChecked].valueCardString
								.length() - 1);
			}

			return resVal;
		}

		int computePoints2(Card[] cards) {
			int val = 0;
			if (cards == null) {
				_cat.debug("error hi error");
				return val;
			}

			int num = cards.length;
			if (num == 0) {
				_cat.debug("error hi error");
				return val;
			}

			_cat.debug("compute pts");
			for (int i = 0; i < num; i++) {
				if (cards[i] == null)
					continue;
			}

			// use rummyJoker to get the value of jokers
			int dummy = 0;

			for (int i = 0; i < num; i++) {
				if (cards[i] == null)
					continue;

				if (cards[i].getIndex() >= 156)
					continue;

				if (determineIfJoker(cards[i])) {
					val += 0;
				} else {
					dummy = cards[i].getHighBJRank();
					if (dummy > 10 || dummy == 0)
						dummy = 10;
					val += dummy;
				}
			}

			if (val > 120)
				val = 120;// this is the max that a player has to pay as part of
							// penalty on cards
			return val;
		}

		// Vikas
		// ****************************************************************************************************

		public void run() {
			while (_keepServicing) {
				String moveStr = null;
				try {
					if (!noMovesToBeAccepted) {
						Presence p = fetch(_tid);
						moveStr = p.getLatestMoveStr();
						_cat.debug("movestr from run : " + moveStr);
						if (moveStr != null) {
							processStr(moveStr, p);
						}
					}
				} catch (NoSuchElementException e) {
				} catch (Exception ex) {
				}

				// now check if game is going to be ended - someone made a valid
				// declaration and we are waiting for all cards messages
				// idPlayerValidDeclared
				if (_gameOngoing
						&& idPlayerValidDeclared != -1
						&& (System.currentTimeMillis() - _nextMoveExpTime) > 21000) { // should
																						// be
																						// 65
																						// seconds
					noMovesToBeAccepted = true;
					declareGameOver(idPlayerValidDeclared);
					idPlayerValidDeclared = -1;
					_nextMovePlayer = -1;
					noMovesToBeAccepted = false;
					_nextMoveExpTime = System.currentTimeMillis();
				}

				// now check if someone has timed out his moves
				if (_gameOngoing
						&& (System.currentTimeMillis() - _nextMoveExpTime) > 30000) { 																						// move
					noMovesToBeAccepted = true;
					if (_nextMovePlayer != -1){
						//3rd Aug 2017 - changes for disconnect protection using time bank
						RummyProfile rp = _players[_nextMovePlayer];
						boolean useTB = true;
						if (!rp.getUsingTimeBank()){
							if (rp.isEligibleTimeBank()){
								rp.setUsingTimeBank(true);
								broadcastMessage(-1);
							}
							else {
								useTB = false;
							}
						}
						else {
							//player is already using time bank
							if (System.currentTimeMillis() - rp.getTimeBankExpTime() >= 0){
								//time bank chunk has expired.
								useTB = false;
							}
						}
						
						if (!useTB)
							handleTimedOut(false);
					}
					noMovesToBeAccepted = false;
				}

				if (!_gameOngoing && _nextMoveExpTime != -1
						&& System.currentTimeMillis() - _nextMoveExpTime > 8000) {
					
					for (int m = 0; m < MAX_PLAYERS; m++){
						if (_players[m] != null
								&& (isLeft(_players[m]) || isRemoved(_players[m]))) {
							// removed condition for waiting player to make a move -
							// that was creating issues with players who are waiting
							// to join next game
							_cat.debug("nothing from this player, kick him out : "
											+ _players[m].getName());
							// before sending the message we should check if client
							// is showing the result window
							// if so, this message would curtail the beautiful,
							// rapturous experience of a winning player
							sendMessageKickedOut(m, 2);
			
							// remove the player
							_players[m] = null;
							
							if (getCountTotalPlayers() > 0 && !toSendMsg){
								toSendMsg = true;
								resetTable();
								broadcastMessage(-1);
							}
						}
					}

					int countPlayers = getCntStatusActivePlayers() + getCountSitInReqPlayers();
					if (getCountTotalPlayers() >= 2 && counterGameNotStarted < 8) {
						if (countPlayers >= 2)
							fixDealerFirst();
						else {
							counterGameNotStarted++;
							resetTable();
							if (countPlayers == 1)
								broadcastMessage(-1);
						}
					} else {
						// clear out the jokers for it creates a wrong
						// impression
						_rummyCardJoker = null;
						_rummyUpJoker = null;
						_rummyDownJoker = null;
						_discardCard = null;
						_dealer = -1;
						_rummyPlayer = -1;
						
						if (System.currentTimeMillis() - _nextMoveExpTime > 15000) {
							boolean flag = false;
							for (int m = 0; m < MAX_PLAYERS; m++) {
								if (_players[m] != null && !isActive(_players[m])) {
									sendMessageKickedOut(m, 2);
									// remove the player
									_players[m] = null;
									flag = true;
								}
							}
							
							if (flag){
								broadcastMessage(-1);
								counterGameNotStarted = 0;
								_nextMoveExpTime = System.currentTimeMillis();
							}
						}
						
						// remove the removed players now
						if (System.currentTimeMillis() - _nextMoveExpTime > 60000) {
							for (int m = 0; m < MAX_PLAYERS; m++) {
								if (_players[m] != null) {
									sendMessageKickedOut(m, 2);
									// remove the player
									_players[m] = null;
								}
							}
							counterGameNotStarted = 0;
							_nextMoveExpTime = -1;
						}
						
					}
				}

				if (!_gameOngoing) {
					if (System.currentTimeMillis() - _nextMoveExpTime > 60000) {
						_nextMoveExpTime = System.currentTimeMillis();
						for (int b = 0; b < _observers.size(); b++) {
							RummyProfile pro = (RummyProfile) _observers.get(b);
							// check first if it is just an observor, if so,
							// remove it - there is no game so what will he
							// observe?
							if (pro.getRUMMYStatus() == 0)
								removeObserver(pro);
						}
					}
				}

				// now sleep for 300 ms - in the long run i see it as 10 ms or
				// something
				try {
					Thread.currentThread().sleep(1000);
				} catch (InterruptedException ee) {
					// continue
				}
			}
		}

		private int drawCard() {

			// check if the fresh pile has run out - if so, we need to add the
			// cards of discard pile to fresh pile
			if (_deck.cardinality() >= MAX_CARDS) {
				_cat.debug("WARNING!!!! fresh pile has run out!!!!");
				addCardsToFreshPile(_discardDeck, _indexDiscardDeck);
				_discardDeck = new Card[150];

				// keep the same discard card - don't change it
				_deck.set(_discardCard.getIndex());

				_indexDiscardDeck = 0;
				_discardDeck[_indexDiscardDeck++] = _discardCard;

				// also mark the rummy card joker - we can't give it if we have
				// only one deck
				_deck.set(_rummyCardJoker.getIndex());
			}

			int k = Rng.nextIntBetween(0, 100);
			if (k == 37 || k == 63) {
				// give a joker if it is possible
				if (totalJokersAlreadyDealt < NUM_DECKS) {
					totalJokersAlreadyDealt++;
					_deck.set(160 + totalJokersAlreadyDealt);
					printedJokerDealt = true;
					return 160 + totalJokersAlreadyDealt;
				}
			}

			// also put a condition on deck size - if 1/2 deck is gone and no
			// printed joker is dealt, deliberately give a
			if (_deck.cardinality() >= (int) (MAX_CARDS * 0.4)
					&& _deck.cardinality() < (int) (MAX_CARDS * 0.5)
					&& !printedJokerDealt) {
				if (totalJokersAlreadyDealt < NUM_DECKS) {
					totalJokersAlreadyDealt++;
					_deck.set(160 + totalJokersAlreadyDealt);
					printedJokerDealt = true;
					return 160 + totalJokersAlreadyDealt;
				}
			}

			if (_deck.cardinality() >= (int) (MAX_CARDS * 0.6)
					&& _deck.cardinality() < (int) (MAX_CARDS * 0.7)
					&& !printedJokerDealt) {
				if (totalJokersAlreadyDealt < NUM_DECKS) {
					totalJokersAlreadyDealt++;
					_deck.set(160 + totalJokersAlreadyDealt);
					printedJokerDealt = true;
					return 160 + totalJokersAlreadyDealt;
				}
			}

			if (_deck.cardinality() >= (int) (MAX_CARDS * 0.8)
					&& _deck.cardinality() < (int) (MAX_CARDS * 0.9)
					&& !printedJokerDealt) {
				if (totalJokersAlreadyDealt < NUM_DECKS) {
					totalJokersAlreadyDealt++;
					_deck.set(160 + totalJokersAlreadyDealt);
					printedJokerDealt = true;
					return 160 + totalJokersAlreadyDealt;
				}
			}

			int rand = Rng.nextIntBetween(0, MAX_CARDS);
			while (_deck.get(rand)) {
				rand = Rng.nextIntBetween(0, MAX_CARDS);
			}
			_deck.set(rand);
			// _cat.debug("found this card : " + rand +
			// " , max cards : "
			// + MAX_CARDS);

			if (_deck.cardinality() >= (int) (MAX_CARDS * 0.5)
					|| _deck.cardinality() >= (int) (MAX_CARDS * 0.7)
					|| _deck.cardinality() >= (int) (MAX_CARDS * 0.9))
				printedJokerDealt = false;

			if (rand >= 103)
				rand -= 103;
			else if (rand >= 52)
				rand -= 52;
			return rand;
		}

		// drawCardOneDeck
		private int drawCardOneDeck() {
			int rand = Rng.nextIntBetween(0, 51);
			while (_deck.get(rand)) {
				rand = Rng.nextIntBetween(0, 51);
			}
			_deck.set(rand);
			return rand;
		}

		public void processStr(String movedet, Presence p) {
			// 0,-1,0,143680.64,-1,-1
			int tid = getTID(movedet);// table id, which table he is seated
			int pos = getPos(movedet);// which chair position he is seated
			int moveId = getMoveId(movedet);
			String type = getType(movedet); // applicable for which pile a card
											// is requested - fresh pile or
											// discard pile
			String cardsDet = getCardsDet(movedet); // only applicable for
													// declare and declare
													// loser. has the various
													// cards arranged in groups
			// and also for the case of discarding card - index of card that is
			// being discarded
			_cat.debug("tid : " + tid + " , pos : " + pos
					+ ", moveid : " + moveId + ",type : " + type
					+ " , nextmoveplayer : " + _nextMovePlayer);

			// first handle the case of sit in request
			if (moveId == move_RUMMY_JOINED) {
				// definitely a sit in request ---
				//check here if game is in declare mode expecting losers to send in their card string
				//if so, we can't let this player join the table - he/she has to wait till dust settles
				if (_nextMovePlayer != 111)
					handleGameJoinReq(pos, p);
				return;
			}

			if (_players[pos] == null || _players[pos].getPresence() == null) {
				_cat.debug("wrong wrong wrong rummy profile or presence missing");
				return;
			}
			
			if (moveId == move_RUMMY_CHAT) {
				_lastMove = move_RUMMY_CHAT;
				_lastMovePos = pos;
				_lastMoveString = "Chat";
				chatOn = 1;
				chatMessage = "Pos " + (pos + 1) + ":" + cardsDet;
				broadcastMessage(-1);
				chatOn = 0;
				chatMessage = "";
				return;
			}
			
			if (moveId == move_RUMMY_SITOUT) {
				_lastMove = move_RUMMY_SITOUT;
				_lastMovePos = pos;
				_lastMoveString = "SitOutNextGame";
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " wants to sitout next game.";
				if (isSittingOut(_players[pos])){
					_cat.debug("already sitting out");
					return;
				}
				
				if (!sitoutPlayers.contains(pos))
					sitoutPlayers.add(pos);
				
				if (!_gameOngoing)
					handleSitoutSitin();
				
				broadcastMessage(-1);
				chatMessage = "";
				return;
			}
			
			if (moveId == move_RUMMY_SITIN) {
				_lastMove = move_RUMMY_SITIN;
				_lastMovePos = pos;
				_lastMoveString = "RejoinNextGame";
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " wants to rejoin next game.";
				if (!isSittingOut(_players[pos])){
					_cat.debug("not sitting out so why?");
					return;
				}
				
				if (!sitinPlayers.contains(pos))
					sitinPlayers.add(pos);
				
				if (!_gameOngoing)
					handleSitoutSitin();
				
				broadcastMessage(-1);
				chatMessage = "";
				return;
			}

			if (!_gameOngoing) {
				_cat.debug("game not running, ignore all messages - let them all die!!!!!");
				return;
			}

			RummyProfile prp = _players[pos];

			newCardAdded = "";
			// if nextmove didn't come from designated player, drop the message
			if (_nextMovePlayer == 111 && moveId != move_RUMMY_DECLARE_LOSER) {
				_cat.debug("no moves other than declare loser allowed!!!");
				sendErrorMessage(pos, 3);
				return;
			} else {
				if (_nextMovePlayer != 111 && pos != _nextMovePlayer
						&& moveId != move_RUMMY_DECLARE) {
					_cat.debug("no moves allowed from this pos!!!");
					sendErrorMessage(pos, 0);
					return;
				}
				if ((moveId & _nextMovesAllowed) != moveId
						&& _nextMovePlayer != 111) {
					_cat.debug("these moves not allowed from this pos!!!");
					sendErrorMessage(pos, 0);
					return;
				}
			}

			_players[pos].rummyLeftTableTime = -1;

			// update the rummyLastMoveTime for this player
			_players[pos].rummyLastMoveTime = System.currentTimeMillis();

			if (moveId == move_RUMMY_DECLARE_LOSER) {
				_lastMove = move_RUMMY_DECLARE_LOSER;
				_lastMovePos = pos;
				_lastMoveString = "GameDeclareLoser";
				prp.setRUMMYMovesStr("&GameDeclareLoser^" + cardsDet);
				prp.setRUMMYMoveId(move_RUMMY_DECLARE_LOSER);
				
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " declared";

				// just store the cards arrangement string from player
				prp.cardsStrFromClient = cardsDet;
				_cat.debug("player " + prp.name + " sent cards : " + cardsDet);

				countPlayerResponseDeclareLoser--;
				if (countPlayerResponseDeclareLoser <= 0) {
					// end the game here
					declareGameOver(idPlayerValidDeclared);
					idPlayerValidDeclared = -1;
					_nextMovePlayer = -1;
				}
			}

			if (moveId == move_RUMMY_DECLARE) {
				_lastMove = move_RUMMY_DECLARE;
				_lastMovePos = pos;
				_nextMoveExpTime = System.currentTimeMillis();
				_lastMoveString = "GameDeclare'";
				prp.setRUMMYMovesStr("&GameDeclare^" + cardsDet);
				prp.setRUMMYMoveId(move_RUMMY_DECLARE);
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);

				int typeIndex = -1;

				// special case : if some player does a declare when it is not
				// his turn, he has to sent type as -1
				if (type.compareToIgnoreCase("-1") != 0) {
					// it is not -1, so it is a valid card being discarded
					if (type.compareToIgnoreCase("JO") == 0) {
						typeIndex = 161;
					} else if (type.compareToIgnoreCase("JO1") == 0) {
						typeIndex = 162;
					} else if (type.compareToIgnoreCase("JO2") == 0) {
						typeIndex = 163;
					} else {
						Card ctemp = new Card(type);
						typeIndex = ctemp.getIndex();
						if (typeIndex == -1) {
							sendErrorMessage(pos, 2);
							return;
						}
					}
					// get rid of card - index is in type
					int[] newCards = new int[21];
					int j = 0;
					boolean found = false;
					for (int i = 0; i < 22; i++) {
						if (prp._allotedCards[i].getIndex() != typeIndex
								|| found) {
							newCards[j++] = prp._allotedCards[i].getIndex();
						} else {
							found = true;
						}
					}
					prp._allotedCards = new Card[21];
					for (int i = 0; i < newCards.length; i++) {
						Card crd = new Card(newCards[i]);
						crd.setIsOpened(true);
						prp._allotedCards[i] = crd;
						// _cat.debug("card added : " + crd.toString());
					}
				} else {
					// check if teh player is deliberately sending declare move
					if ((prp.getRUMMYStatus() & status_ACTIVE) != status_ACTIVE) {
						return;
					}
				}

				// check if cards are indeed valid
				boolean flagGameOver = false;
				if (prp._markCheckingCards)
					return;

				prp._markCheckingCards = true;
				idPlayerChecked = pos;
				prp.cardsStrFromClient = cardsDet;
				int[] result = checkValidCards2(prp._allotedCards, 21, 1,
						prp.cardsStrFromClient); // 1
				// means
				// challenger,
				// 0
				// means
				// challenged
				prp._markCheckingCards = false; // has to be done because
												// checkValidCards takes time to
												// complete and we don't want
												// multiple calls
				_cat.debug("penalyt : " + result[0] + ", first life : "
						+ result[1] + " , 2nd life : " + result[2]);
				
				//always discard card even if it is a failed bid to declare
				_discardCard = new Card(typeIndex);
				_discardCard.setIsOpened(false);//16th Nov 17 as per PIR reqmts
				_discardDeck[_indexDiscardDeck++] = _discardCard;
				
				if (result[0] == 0 && result[2] == 1)
					flagGameOver = true;

				if (flagGameOver) {
					
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " made valid declare.";

					_lastMoveString += "Valid:Winner";
					prp.setRUMMYMovesStr("^ValidDeclare");
					idPlayerValidDeclared = pos;
					// for each player, now set the move as declare loser. now
					// keep a count of players from whom messages have to come
					_nextMovesAllowed = move_RUMMY_DECLARE_LOSER;
					_nextMovePlayer = 111;
					broadcastMessage(-1);

					_nextMoveExpTime = System.currentTimeMillis();

					// now keep a track of how many active players are going to
					// respond back
					for (int m = 0; m < MAX_PLAYERS; m++) {
						if (_players[m] != null
								&& m != pos
								&& (isActive(_players[m])
										&& !isRemoved(_players[m]) && !isFolded(_players[m]))) {
							// only non folded players are supposed to make
							// moves
							countPlayerResponseDeclareLoser++;
						}
					}

					if (countPlayerResponseDeclareLoser == 0) {
						declareGameOver(idPlayerValidDeclared);
						idPlayerValidDeclared = -1;
						_nextMovePlayer = -1;
					}
					return;
				} else {
					// apply penalty on player, fold him, continue game
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " made invalid declare.";
					
					_lastMoveString += "Invalid:Folded";
					prp.setRUMMYMovesStr("^ValidFail");
					prp.setRUMMYStatus(status_FOLDED);
					prp._allotedCards = null;
					prp.setRUMMYPoints(120);
					if (!checkGameOver()) {
						// special case - as we are allowing declare to work
						// anytime from any player even from not the nextmovepos
						// player,
						// we have to be careful here. if some such person did
						// declare and was caught with pants down, then we don't
						// have to do anything
						if (pos == _nextMovePlayer) {
							_nextMovesAllowed = move_RUMMY_DECLARE
									| move_RUMMY_NEWCARD | move_RUMMY_FOLD;
							_nextMovePlayer = getNextActivePos(_nextMovePlayer);
							_nextMoveExpTime = System.currentTimeMillis();
						}
						broadcastMessage(-1);
					}
				}
			}

			// if (moveId == move_RUMMY_RUMMYCUT) {
			// //this move can only come from designated player rummyPlayer, no
			// one else can send it
			// _lastMove = move_RUMMY_NEWCARD;
			// _lastMovePos = pos;
			// _lastMoveString = "RummyCut";
			// prp.setRUMMYMoveId(move_RUMMY_NEWCARD);
			// prp.setRUMMYStatus(status_ACTIVE);
			// _nextMoveExpTime = System.currentTimeMillis();
			//
			// //give cards to all players
			// for (int m = 0; m < MAX_PLAYERS; m++) {
			// if (_players[m] != null && _players[m].getPresence() != null) {
			// for (int i = 0; i < 13; i++) {
			// int randCard = drawCard();
			// Card cr = new Card(randCard);
			// cr.setIsOpened(true);
			// _players[m]._allotedCards[i] = cr;
			// }
			// }
			// }
			//
			// //set the next move player - it will be the one on dealer's left
			// _nextMovePlayer = getNextActivePos(_dealer);
			// _nextMovesAllowed = move_RUMMY_DECLARE | move_RUMMY_NEWCARD |
			// move_RUMMY_FOLD;
			// broadcastMessage(-1);
			// }

			if (moveId == move_RUMMY_NEWCARD) {
				int typeO = 0;

				try {
					typeO = Integer.parseInt(type);
				} catch (NumberFormatException ex) {
					_cat.debug("wrong string for type!!!!!");
					sendErrorMessage(pos, 1);
					return;
				}

				_cat.debug("reached here 3333333333333333333333333 "
						+ typeO + " , cards length : "
						+ prp._allotedCards.length);

				// if client sends this message again then 2nd and subsequent
				// message have to be dropped
				if (prp._allotedCards.length >= 22) {
					_cat.debug("already gave one card, how many does it want more !!!");
					sendErrorMessage(pos, 0);
					return;
				}

				// use type to find if it is frsh pile card (0) or discard (1)
				Card cr = null;
				if (typeO == 0) {
					_lastMoveString = "ChoseFreshCard";
					int randCard = drawCard();
					cr = new Card(randCard);
					cr.setIsOpened(true);
					prp.setRUMMYMovesStr("&GetFresh^" + cr.toString());
					newCardAdded = cr.toString();
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " chose fresh card.";
				} else {
					cr = new Card(_discardCard.getIndex());
					cr.setIsOpened(true);

					//added on 20th Sep 2017 - 
					//joker card in discarded cards can't be taken
					if (determineIfJoker(cr)){
						_cat.debug("can't take joker card from discards...");

						//check now if this is the very first move of very first player
						//then it is allowed
						boolean firstMove = true;
						for (int k = 0; k < MAX_PLAYERS; k++){
							if (_players[k] != null && _players[k]._firstMoveDone)
								firstMove = false;
						}
						
						if (!firstMove) {
							sendErrorMessage(pos, 4);
							return;
						}
					}
					
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " chose discarded card";
					_lastMoveString = "ChoseDiscardedCard";
					prp.setRUMMYMovesStr("&GetDisc^" + _discardCard.toString());
					
					_discardDeck[_indexDiscardDeck - 1] = null;
					_indexDiscardDeck--;
					if (_indexDiscardDeck > 0)
						_discardCard = _discardDeck[_indexDiscardDeck - 1];
					else
						_discardCard = null;

					newCardAdded = cr.toString();
				}

				_cat.debug("came here : " + _lastMoveString
						+ " , card : " + cr.toString());
				
				prp._firstMoveDone = true;
//				_nextMoveExpTime = System.currentTimeMillis();
				_lastMove = move_RUMMY_NEWCARD;
				_lastMovePos = pos;
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);

				prp.setRUMMYMoveId(move_RUMMY_NEWCARD);
				prp.setRUMMYStatus(status_ACTIVE);


				Card[] clonedCards = prp._allotedCards.clone();
				prp._allotedCards = new Card[22];
				for (int i = 0; i < 21; i++) {
					prp._allotedCards[i] = clonedCards[i];
				}
				prp._allotedCards[21] = cr;

				// no need to change player
				if (!checkGameOver()) {
					_nextMovesAllowed = move_RUMMY_DECLARE | move_RUMMY_DISCARD;
					_nextMoveExpTime = System.currentTimeMillis();
					broadcastMessage(-1);
				}
			}

			if (moveId == move_RUMMY_DISCARD) {
				_nextMoveExpTime = System.currentTimeMillis();
				_lastMove = move_RUMMY_DISCARD;
				_lastMovePos = pos;
				_lastMoveString = "DiscardCard:" + type;
				prp.setRUMMYStatus(status_ACTIVE);
				prp.setRUMMYMoveId(move_RUMMY_DISCARD);
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);

				int typeIndex = -1;

				if (type.compareToIgnoreCase("JO") == 0) {
					typeIndex = 161;
				} else if (type.compareToIgnoreCase("JO1") == 0) {
					typeIndex = 162;
				} else if (type.compareToIgnoreCase("JO2") == 0) {
					typeIndex = 163;
				} else {
					Card ctemp = new Card(type);
					typeIndex = ctemp.getIndex();
					if (typeIndex == -1) {
						sendErrorMessage(pos, 2);
						return;
					}
				}
				// get rid of card - index is in type
				// get rid of card - index is in type
				int[] newCards = new int[21];
				int j = 0;
				boolean found = false;
				for (int i = 0; i < 22; i++) {
					if (prp._allotedCards[i].getIndex() != typeIndex || found) {
						newCards[j++] = prp._allotedCards[i].getIndex();
					} else {
						found = true;// found 1st instance of this card being
										// removed. if there are 2 cards and
										// player wants to get rid of 1, then
										// other should stay.
					}
				}
				prp._allotedCards = new Card[21];
				for (int i = 0; i < newCards.length; i++) {
					Card crd = new Card(newCards[i]);
					crd.setIsOpened(true);
					prp._allotedCards[i] = crd;
					// _cat.debug("card added : " + crd.toString());
				}

				_discardCard = new Card(typeIndex);
				_discardCard.setIsOpened(true);
				_discardDeck[_indexDiscardDeck++] = _discardCard;
				
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " discarded " + _discardCard.toString();
				
				//remember the card discarded
				prp.setRUMMYMovesStr("&Discard");
				if (typeIndex >= 161)
					prp.setRUMMYMovesStr("^JO");
				else
					prp.setRUMMYMovesStr("^" + _discardCard.toString());

				if (!checkGameOver()) {
					_nextMovePlayer = getNextActivePos(_nextMovePlayer);
					_nextMovesAllowed = move_RUMMY_DECLARE | move_RUMMY_NEWCARD
							| move_RUMMY_FOLD;
					broadcastMessage(-1);
				}
			}

			if (moveId == move_RUMMY_FOLD) {
				_nextMoveExpTime = System.currentTimeMillis();
				_lastMove = move_RUMMY_FOLD;
				_lastMovePos = pos;
				_lastMoveString = "Folded";
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);
				
				prp.setRUMMYMovesStr("&Pack");
				prp.setRUMMYStatus(status_FOLDED);
				prp.setRUMMYMoveId(move_RUMMY_FOLD);
				
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " folded.";

				if (prp._firstMoveDone)
					prp.setRUMMYPoints(70);
				else {
					prp.setRUMMYPoints(30);
					// player didn't even play one card, so put his cards in
					// fresh pile
					addCardsToFreshPile(prp._allotedCards, 21);
				}

				if (!checkGameOver()) {
					// game not over
					_nextMovePlayer = getNextActivePos(_nextMovePlayer);
					_nextMovesAllowed = move_RUMMY_DECLARE | move_RUMMY_NEWCARD
							| move_RUMMY_FOLD;
					broadcastMessage(-1);
				}
			}

		}

		public void fixDealerFirst() {
			if (_gameOngoing)
				return;

			// now clear out the all in players list
			resetTable();
			
			_gameOngoing = true;

			if (true) {

				_deck.clear();
				fixingDealerOngoing = true;
				
				counterGameNotStarted = 0;

				for (int m = 0; m < MAX_PLAYERS; m++) {
					if (_players[m] != null
							&& _players[m].getPresence() != null && isActive(_players[m])) {
						_players[m].fixPosCard = drawCardOneDeck();
					}
				}

				int least = 999, leastCardBearer = -1;
				for (int m = 0; m < MAX_PLAYERS; m++) {
					if (_players[m] != null
							&& _players[m].getPresence() != null && isActive(_players[m])) {
						if (_players[m].fixPosCard % 13 < least) {
							least = _players[m].fixPosCard % 13;
							leastCardBearer = m;
						} else if (_players[m].fixPosCard % 13 == least) {
							// if the ranks are same, then check for suits
							if (leastCardBearer != -1) {
								if (_players[m].fixPosCard < _players[leastCardBearer].fixPosCard) {
									leastCardBearer = m;
								}
							}
						}
					}
				}

				_dealer = leastCardBearer;
				_rummyPlayer = getRightOfPlayer(_dealer);
				for (int m = 0; m < MAX_PLAYERS; m++) {
					int index = _dealer - (m + 1);
					if (index < 0)
						index = 5 + index + 1;
					if (_players[index] != null
							&& _players[index].getPresence() != null
							&& isActive(_players[index]))
						_cardDealingOrder += index + "'";
				}
				_cardDealingOrder = _cardDealingOrder.substring(0,
						_cardDealingOrder.length() - 1);
				
				_nextMovePlayer = -1;
				
				broadcastMessage(-1);

				// now start the game - have to wait for some seconds to give
				// time to client to display the cards and the new dealer and
				// rummy player
				// based on sitting players, let us see if we can start a game
				try {
					Thread.currentThread().sleep(5000);
				} catch (InterruptedException ee) {
					// continue
				}
				fixingDealerOngoing = false;
//				fixingDealerNextHand = false;
//				countHandsAfterDealerFix = 0;
			} 
//			else {
//				countHandsAfterDealerFix++;
//				if (countHandsAfterDealerFix >= 3)
//					fixingDealerNextHand = true;
//			}

			startGameIfPossible();
		}

		public void startGameIfPossible() {
			// game can begin ---
			// now clear out the all in players list
			// resetTable();

			int _countPlayersInit = getCountActivePlayers();
			_cat.debug("startgame - " + _countPlayersInit);
			if (_countPlayersInit >= 2) {
				
				toSendMsg = false;
				
				_amtWonString = "";

//				if (_countPlayersInit > 4)
//					NUM_DECKS = 3;
//				else if (_countPlayersInit > 2)
//					NUM_DECKS = 2;
//				else
//					NUM_DECKS = 1;\
				
				NUM_DECKS = 3;

				MAX_CARDS = 52 * NUM_DECKS - 1;

				initGame();

				// now initialize the variables
				for (int m = 0; m < MAX_PLAYERS; m++) {
					if (_players[m] != null
							&& !isSittingOut(_players[m])
					// && _players[m].getPresence() != null
					) {
						_players[m]._firstMoveDone = false;
						_players[m]._allotedCards = new Card[13];
						_players[m].setRUMMYWinAmt(0);
						_players[m].setRUMMYStatus(status_ACTIVE);
						_players[m].setRUMMYValCards(0);
						_players[m].setRUMMYValGiven(0);
						_players[m].setRUMMYPoints(0);
						_players[m].marriageCount = 0;
						_players[m].pjcount = 0;
						_players[m].highValCutJoker = 0;
						_players[m].highValDownJoker = 0;
						_players[m].highValUpJoker = 0;
						_players[m].valueCardString = "";
						_players[m].cardsStrFromClient = "";
					}
				}

				idPlayerValidDeclared = -1;
				idPlayerChecked = -1;

				// now set teh _cardDealingOrder starting from right of dealer -
				// haha - right means decrement by 1
				// but first check if dealer is still pointing to a valid player
				// - if not, shift it now
				if (_dealer == -1)
					_dealer = 0;
				if (_players[_dealer] == null || isRemoved(_players[_dealer])
						|| isLeft(_players[_dealer])) {
					getNextActivePos(_dealer);
				}

				_rummyPlayer = getRightOfPlayer(_dealer);
				
				for (int m = 0; m < MAX_PLAYERS; m++) {
					int index = _dealer - (m + 1);
					if (index < 0)
						index = 5 + index + 1;
					if (_players[index] != null
							&& _players[index].getPresence() != null
							&& isActive(_players[index]))
						_cardDealingOrder += index + "'";
				}
				_cardDealingOrder = _cardDealingOrder.substring(0,
						_cardDealingOrder.length() - 1);

				lastRespTime = System.currentTimeMillis();

				_winnerString = "";
				rummygrid = setNextGameRunId();
				// _nextMovesAllowed = move_RUMMY_RUMMYCUT;
				_nextMovesAllowed = move_RUMMY_DECLARE | move_RUMMY_NEWCARD
						| move_RUMMY_FOLD;

				// give cards to all players
				for (int m = 0; m < MAX_PLAYERS; m++) {
					if (_players[m] != null
							&& _players[m].getPresence() != null && !isSittingOut(_players[m])) {
						//dealer, rummy joker card, points per rupee, table id
						_players[m].setRUMMYMovesStr("&TID^" + _tid);
						_players[m].setRUMMYMovesStr("&Dealer^" + _dealer);
						_players[m].setRUMMYMovesStr("&RummyCard^" + _rummyCardJoker.toString());
						_players[m].setRUMMYMovesStr("&UpperCard^" + _rummyUpJoker.toString());
						_players[m].setRUMMYMovesStr("&LowerCard^" + _rummyDownJoker.toString());
						_players[m].setRUMMYMovesStr("&PtsPerRupee^" + POINTSPERRUPEE);
						
						_players[m]._allotedCards = new Card[21];// always clear
																	// the hand
																	// here

						_players[m].setRUMMYMovesStr("&Cards^");
						
						for (int i = 0; i < 21; i++) {
							int randCard = drawCard();
							Card cr = new Card(randCard);
							cr.setIsOpened(true);
							_players[m]._allotedCards[i] = cr;
							
							_players[m].setRUMMYMovesStr(cr.toString());
							if (i < 20)
								_players[m].setRUMMYMovesStr("`");
						}
					}
				}

				broadcastMessage(-1);
				// sleep for 10 seconds to allow clients to distribute cards
				try {
					Thread.currentThread().sleep(2000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

				_nextMoveExpTime = System.currentTimeMillis();
				_gameStartTime = Calendar.getInstance();
				_nextMovePlayer = _rummyPlayer;
				broadcastMessage(-1);
			} else {
				_gameOngoing = false;
				_dealer = -1;
				_rummyPlayer = -1;
				// for the unfortunate player who might be seated on teh table
				if (_countPlayersInit == 1)
					broadcastMessage(-1);
			}
		}
		
		public String encrypt(String text){
			String key = "DealServer123456"; // 128 bit key
			Key aesKey = new SecretKeySpec(key.getBytes(), "AES");
			_cat.debug("aeskey got " + aesKey);
			try {
	            Cipher cipher = Cipher.getInstance("AES");
	            _cat.debug("cipher opbtained ... " + cipher);
	            // encrypt the text
	            cipher.init(Cipher.ENCRYPT_MODE, aesKey);
	            byte[] encrypted = cipher.doFinal(text.getBytes());
//	            String str = new String(encrypted, "ISO-8859-1");
	            String str = Base64.encode(encrypted);
	            _cat.debug("encryped : " + str);
	            return str;
			}
			catch(Exception ex){
				_cat.debug("exception : " + ex.getMessage());
			}
			
			return null;
		}

		public void broadcastMessage(int winnerPos) {
			_cat.debug("broadcasting response!!!");
			StringBuffer temp = new StringBuffer();
			temp.append("Rummy21Server=Rummy21Server");
			temp.append(",gid=").append(_gid);
			temp.append(",grid=").append(rummygrid);
			temp.append(",rummygrid=").append(rummygrid);
			temp.append(",TID=").append(_tid);

			temp.append(",Dealer=").append(_dealer);
			temp.append(",RummyPlayer=").append(_rummyPlayer);
			temp.append(",GameOn=").append(_gameOngoing);
			
			if (!_gameOngoing){
				double to = 8000 - (System.currentTimeMillis() - _nextMoveExpTime);
				if (to < 0)
					to = 0;
				int toint = (int)(to/1000);
				temp.append(",TimerNG=").append(toint);
			}
			
			if (_nextMovePlayer != -1 && _nextMovePlayer < MAX_PLAYERS && _players[_nextMovePlayer] != null && winnerPos == -1){
				//for time bank
				if (_players[_nextMovePlayer].getUsingTimeBank()){
					temp.append(",DiscProtOn=").append((_players[_nextMovePlayer].getTimeBank()) * 10);
				}
			}

			if (_rummyCardJoker != null)
				temp.append(",RummyJoker=").append(_rummyCardJoker.toString());
			else
				temp.append(",RummyJoker=");

			if (_rummyUpJoker != null)
				temp.append(",RummyUpJoker=").append(_rummyUpJoker.toString());
			else
				temp.append(",RummyUpJoker=");

			if (_rummyDownJoker != null)
				temp.append(",RummyDownJoker=").append(
						_rummyDownJoker.toString());
			else
				temp.append(",RummyDownJoker=");

			if (_discardCard != null && idPlayerValidDeclared == -1)
				temp.append(",DiscardCard=").append(_discardCard.toString());
			else
				temp.append(",DiscardCard=");
			
			temp.append(",chatMsgOn=").append(chatOn);
			if (!chatMessage.isEmpty()){
				String strcrypt = encrypt(chatMessage);
				temp.append(",chatMsg=").append(strcrypt);
//				_cat.debug(decrypt(strcrypt));
				chatMessage = "";
			}
			else
				temp.append(",chatMsg=");
			
			if (idPlayerValidDeclared != -1) {
				temp.append(",ValidDecPlyr=").append(
						_players[idPlayerValidDeclared].name);
				temp.append(",ValidDecPlyrId=").append(
						idPlayerValidDeclared);
			}

			temp.append(",NextMovePos=").append(_nextMovePlayer);
			temp.append(",NextMoveId=").append(_nextMovesAllowed);
			temp.append(",LastMove=").append(_lastMove);
			temp.append(",LastMovePos=").append(_lastMovePos);
			temp.append(",LastMoveType=").append(_lastMoveString);

			temp.append(",DealingOrder=").append(_cardDealingOrder);

			// add the bit about checking cards of players
			if (fixingDealerOngoing) {
				String str = "";
				for (int i = 0; i < MAX_PLAYERS; i++) {
					if (_players[i] != null && isActive(_players[i])) {
						Card tempCr = new Card(_players[i].fixPosCard);
						str += i + "`" + tempCr.toString() + "'";
					}
				}
				str = str.substring(0, str.length() - 1);
				temp.append(",FixDealerProcess=").append(1);
				temp.append(",FixDealerCards=").append(str);
			}

			if (winnerPos != -1 && _players[winnerPos] != null && _players[winnerPos]._allotedCards != null) {
				temp.append(",Winner=").append(winnerPos);
				String str = "";
				for (int k = 0; k < _players[winnerPos]._allotedCards.length; k++)
					str += _players[winnerPos]._allotedCards[k].toString()
							+ "'";
				str = str.substring(0, str.length() - 1);
				temp.append(",WinnerCards=").append(str);
				if (!_players[winnerPos].cardsStrFromClient.isEmpty())
					temp.append(",WinnerCardsString=").append(
							_players[winnerPos].cardsStrFromClient);

				temp.append(",Penalty=").append(_winnerString);
				temp.append(",WinPoints=").append(
						_players[winnerPos].getRUMMYWinAmt());
				temp.append(",AmountsWon=").append(_amtWonString);
			}
			// now create that amalgam of all players status, pos, chips
			StringBuffer tempPlayerDet = new StringBuffer();
			int nCount = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null) {
					nCount++;
					tempPlayerDet.append("'" + i + ":" + _players[i].getName()
							+ ":");
					tempPlayerDet.append(_players[i].getRUMMYStatus() + ":");
					if (_players[i].getPresence() != null)
						tempPlayerDet.append(_players[i].getPresence().getAmtAtTable());
					else
						tempPlayerDet.append("0.0");
				}
			}
			temp.append(",PlayerDetails=" + nCount + tempPlayerDet);

			_cat.debug("reaching here : temp : " + temp);

			// this temp can be now sent to all observers on the table
			for (int i = 0; i < _observers.size(); i++) {
				RummyProfile pro = (RummyProfile) _observers.get(i);
				// if (!pro.isRummyPlayer()) {
				_cat.debug("sending to pro : " + pro.getName());
				sendMessage(temp, pro);
				// }
			}
			// for each presence, call sendMessage with their individual data
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null) {
					StringBuffer tempPlayerData = new StringBuffer(temp);
					tempPlayerData.append(",PlayerPos=").append(i);
					if (_players[i]._allotedCards != null
							&& !fixingDealerOngoing) {
						String str = "";
						for (int k = 0; k < _players[i]._allotedCards.length; k++)
							str += _players[i]._allotedCards[k].toString()
									+ "'";
						str = str.substring(0, str.length() - 1);
						tempPlayerData.append(",Cards=" + str);
					} else
						tempPlayerData.append(",Cards=");

					tempPlayerData.append(",NewCardAdded=" + newCardAdded);
					sendMessage(tempPlayerData, _players[i]);
				}
			}
		}

		public void sendMessage(StringBuffer resp, RummyProfile pr) {
			Game g = Game.game(_gid);
			Casino cg = (Casino) g;
			com.hongkong.game.resp.Response r = (com.hongkong.game.resp.Response) new MoveResponse(
					cg, resp, pr.getPresence());
			try {
				GameProcessor.deliverResponse(r);
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}

		public void sendMessageKickedOut(int prpos, int resCode) {
			StringBuffer temp = new StringBuffer();
			temp.append("Rummy21Server=Rummy21Server");
			temp.append(",gid=").append(_gid);
			temp.append(",grid=").append(rummygrid);
			temp.append(",rummygrid=").append(rummygrid);
			temp.append(",TID=").append(_tid);
			temp.append(",KickedOut=").append(prpos);

			// for reason
			if (resCode == 0) {
				temp.append(",KickReason=").append("Player left!");
			} else if (resCode == 1) {
				temp.append(",KickReason=").append(
						"Didn't play in last 5 hands!");
			} else if (resCode == 2) {
				temp.append(",KickReason=").append("No action for long!");
			}

			StringBuffer tempPlayerDet = new StringBuffer();
			int nCount = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null) {
					nCount++;
					tempPlayerDet.append("'" + i + ":" + _players[i].getName()
							+ ":");
					tempPlayerDet.append(_players[i].getRUMMYStatus() + ":"
							+ _players[i].getPresence().getAmtAtTable());
				}
			}
			temp.append(",PlayerDetails=" + nCount + tempPlayerDet);
			// this temp can be now sent to all observers on the table
			for (int i = 0; i < _observers.size(); i++) {
				RummyProfile pro = (RummyProfile) _observers.get(i);
				if (!pro.isRummyPlayer()) {
					sendMessage(temp, pro);
				}
			}
			// for each presence, call sendMessage with their individual data
//			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[prpos] != null) {
					StringBuffer tempPlayerData = new StringBuffer(temp);
					tempPlayerData.append(",PlayerPos=").append(prpos);
					sendMessage(tempPlayerData, _players[prpos]);
				}
	//		}
		}

		public void sendErrorMessage(int prpos, int resCode) {
			StringBuffer temp = new StringBuffer();
			temp.append("Rummy21Server=Rummy21Server");
			temp.append(",gid=").append(_gid);
			temp.append(",grid=").append(rummygrid);
			temp.append(",rummygrid=").append(rummygrid);
			temp.append(",TID=").append(_tid);

			// for reason
			if (resCode == 0) {
				temp.append(",MsgDropped=").append("WrongMove");
			} else if (resCode == 1) {
				temp.append(",MsgDropped=").append("WrongCard");
			} else if (resCode == 2) {
				temp.append(",MsgDropped=").append("CardNotYours");
			}else if (resCode == 3) {
				temp.append(",MsgDropped=").append("OnlyDeclareLoserAllowed");
			} else if (resCode == 4) {
				temp.append(",MsgDropped=").append("CantTakeDiscardedJoker");
			} else if (resCode == 11) {
				temp.append(",PenalCards=" + globalPenal + "'firstLife="
						+ globalFirstLife + "'secondLife=" + globalSecondLife);
			} else {
				temp.append(",MsgDropped=NotAllowed");
			}

			temp.append(",Dealer=").append(_dealer);
			temp.append(",RummyPlayer=").append(_rummyPlayer);
			temp.append(",GameOn=").append(_gameOngoing);

			if (_rummyCardJoker != null)
				temp.append(",RummyJoker=").append(_rummyCardJoker.toString());
			else
				temp.append(",RummyJoker=");

			if (_rummyUpJoker != null)
				temp.append(",RummyUpJoker=").append(_rummyUpJoker.toString());
			else
				temp.append(",RummyUpJoker=");

			if (_rummyDownJoker != null)
				temp.append(",RummyDownJoker=").append(
						_rummyDownJoker.toString());
			else
				temp.append(",RummyDownJoker=");

			if (_discardCard != null && idPlayerValidDeclared == -1)
				temp.append(",DiscardCard=").append(_discardCard.toString());
			else
				temp.append(",DiscardCard=");

			temp.append(",NextMovePos=").append(_nextMovePlayer);
			temp.append(",NextMoveId=").append(_nextMovesAllowed);
			temp.append(",LastMove=").append(_lastMove);
			temp.append(",LastMovePos=").append(_lastMovePos);
			temp.append(",LastMoveType=").append(_lastMoveString);

			temp.append(",DealingOrder=").append(_cardDealingOrder);

			// add the bit about checking cards of players
			// now create that amalgam of all players status, pos, chips
			StringBuffer tempPlayerDet = new StringBuffer();
			int nCount = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null) {
					nCount++;
					tempPlayerDet.append("'" + i + ":" + _players[i].getName()
							+ ":");
					tempPlayerDet.append(_players[i].getRUMMYStatus() + ":"
							+ _players[i].getPresence().getAmtAtTable());
				}
			}
			temp.append(",PlayerDetails=" + nCount + tempPlayerDet);

			// for each presence, call sendMessage with their individual data
			int i = prpos;
			if (_players[i] != null) {
				StringBuffer tempPlayerData = new StringBuffer(temp);
				tempPlayerData.append(",PlayerPos=").append(i);
				if (_players[i]._allotedCards != null && !fixingDealerOngoing) {
					String str = "";
					for (int k = 0; k < _players[i]._allotedCards.length; k++)
						str += _players[i]._allotedCards[k].toString() + "'";
					str = str.substring(0, str.length() - 1);
					tempPlayerData.append(",Cards=" + str);
				} else
					tempPlayerData.append(",Cards=");

				tempPlayerData.append(",NewCardAdded=" + newCardAdded);
				sendMessage(tempPlayerData, _players[i]);
			}
		}
	}

	private int getTID(String movedet) {

		String[] betString = movedet.split(",");
		String tid = betString[0];
		return Integer.parseInt(tid);
	}

	private int getPos(String movedet) {

		String[] betString = movedet.split(",");
		String pos = betString[1];
		return Integer.parseInt(pos);
	}

	private int getMoveId(String movedet) {

		String[] betString = movedet.split(",");
		String moveid = betString[2];
		return Integer.parseInt(moveid);
	}

	// private double getAmt(String movedet) {
	//
	// String[] betString = movedet.split(",");
	// String amt = betString[3];
	// return Double.parseDouble(amt);
	// }

	private String getType(String movedet) {

		String[] betString = movedet.split(",");
		String amt = betString[3];
		return amt;
	}

	// getCardsDet
	private String getCardsDet(String movedet) {

		String[] betString = movedet.split(",");
		String amt = betString[4];
		return amt;
	}

	public StringBuffer processMove(Player.Presence p, String movedet) {
		// deliberately keeping it empty, logic moved to rummyMove()
		return null;
	}

	public int findNextInValidTable() {
		for (int i = 0; i < _tables.length; i++) {
			if (!_tables[i].validTable)
				return i;
		}
		return -1;
	}

	// rummyReload
	public StringBuffer rummyReload(Player.Presence p, String movedet) {
		StringBuffer buf = new StringBuffer();

		int tid = getTID(movedet);// table id
		if (tid >= MAX_TABLES || tid < 0 || !_tables[tid].validTable) {
			buf.append("Rummy21Server=Rummy21Server,grid=")
					.append(-1)
					.append(",MsgDropped=InvalidTable,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}

		int pos = getPos(movedet);
		if (pos < 0 || pos > MAX_PLAYERS) {
			buf.append("Rummy21Server=Rummy21Server,grid=")
					.append(-1)
					.append(",MsgDropped=InvalidPos,player-details=" + p.name()
							+ "|" + p.netWorth());
			return buf;
		}

		_tables[tid]._players[pos].setReloadReq(true);
		buf.append("Rummy21Server=Rummy21Server,grid=").append(p.getGRID())
				.append(",player-details=" + p.name() + "|" + p.netWorth());
		return buf;
	}

	public StringBuffer rummySitIn(Player.Presence p, String movedet) {
		// 1,-1,0,99915.35
		int tid = getTID(movedet);// table id, which table he wants to be seated
		int pos = getPos(movedet);// which chair position he wants to be seated
		int moveId = getMoveId(movedet);
		// double amt = getAmt(movedet);
		String type = getType(movedet);
		String cardsDet = getCardsDet(movedet);
		// int sel = getSel(movedet);

		StringBuffer buf = new StringBuffer();// for response back to player
		_cat.debug("Rummy21Server game - sit in req --" + movedet);

		if (movedet == null || movedet.equals("null")) {
			return null;
		}
		if (tid >= MAX_TABLES || tid < 0 || !_tables[tid].validTable) {
			buf.append("Rummy21Server=Rummy21Server,grid=")
					.append(-1)
					.append(",TID=").append(tid)
					.append(",MsgDropped=InvalidTable,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}

		int origPos = -1;
		origPos = _tables[tid].findPos(p);
		if (moveId == 0) {
			// just wants to observe, it is ok
			// put a conditon over here for the plaeyrs who want to come back
			// they had placed some bets and are still valid players on the
			// table
			if (origPos != -1) {
				// found him, seated already.
				_cat.debug("seated already from rummysitin : "
						+ origPos);
			} else {

				if (p.getAmtAtTable() < 120 * _tables[tid].POINTSPERRUPEE) {
					buf.append("RummyServer=RummyServer,grid=")
							.append(-1)
							.append(",TID=").append(tid)
							.append(",MsgDropped=PlayerBroke,player-details="
									+ p.name() + "|" + p.netWorth());
					return buf;
				}
				// now check if there is space on the table
//				if (_tables[tid].getCurrentPlayersCount() >= _tables[tid]
//						.getMaxPlayers()) {
//					buf.append("RummyServer=RummyServer,grid=")
//							.append(p.getGRID())
//							.append(",TID=").append(tid)
//							.append(",MsgDropped=TableFull,player-details="
//									+ p.name() + "|" + p.netWorth());
//					return buf;
//				}
				// create a new rummyprofile for this presence
				RummyProfile kp = new RummyProfile();
				kp.setName(p.name());
				kp.setGameStartWorth(p.getAmtAtTable());
				kp.setPresence(p);
				p.setKPIndex(_tables[tid].addObserver(kp));
				kp.setRUMMYStatus(0);
				kp.rummyLastMoveTime = System.currentTimeMillis();
				buf.append("Rummy21Server=Rummy21Server,grid=")
						.append(-1)
						.append(",TID=").append(tid);
						
				if (_tables[tid].getCountTotalPlayers() <= 0)
						buf.append(",player-details=" + p.name() + "|"
								+ p.netWorth());
				else {
					String str = "";
					for (int k = 0; k < _tables[tid]._players.length; k++){
						
						if (_tables[tid]._players[k] != null)
							str += _tables[tid]._players[k].getName() + "|" + _tables[tid]._players[k].getPresence().netWorth() + "`";
						
					}
					str = str.substring(0, str.length() - 1);
					
					buf.append(",player-details=" + str);
				}

				return buf;
			}
		}

		// wants to sit as a player
		// the checks below are for a player who wants to sit on the table
		// he/she should have enough money on table
		// now check for amt on table -- if 0, set it properly
		// if (origPos == -1 && amt != p.getAmtAtTable()) {
		// _cat.debug("do not even respond .....");
		// //return null;
		// }
		if (origPos == -1
				&& p.getAmtAtTable() < 120 * _tables[tid].POINTSPERRUPEE) {
			buf.append("Rummy21Server=Rummy21Server,grid=")
					.append(-1)
					.append(",TID=").append(tid)
					.append(",MsgDropped=PlayerBroke,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}
		// now check if there is space on the table
		if (origPos == -1
				&& _tables[tid].getCurrentPlayersCount() >= _tables[tid]
						.getMaxPlayers()) {
			buf.append("Rummy21Server=Rummy21Server,grid=")
					.append(p.getGRID())
					.append(",TID=").append(tid)
					.append(",MsgDropped=TableFull,player-details=" + p.name()
							+ "|" + p.netWorth());
			return buf;
		}

		// need to update the pos in the move det string to be shard with Rummy
		// thread
		StringBuffer movedet1 = new StringBuffer();
		int obsPos = origPos;
		if (obsPos == -1) {
			// player not seated already - check in observors list
			obsPos = _tables[tid].findObsPos(p);
			movedet1.append(tid).append(",").append(obsPos).append(",")
					.append(moveId).append("," + type).append("," + cardsDet);
		} else {
			movedet1.append(tid).append(",").append(obsPos).append(",")
					.append(64).append("," + type).append("," + cardsDet);
		}
		// if obsPos is still -1, then there is a major issue. chuck de phatte
		if (obsPos == -1) {
			_cat.debug("tid : " + tid + " name : " + p.name()
					+ " not found!!!!!!");
			return null;
		}

		_cat.debug("movedet1 : " + movedet1.toString());
		// send this message to the table
		p.setLatestMoveStr(movedet1.toString());
		add(tid, p);

		return null;
	}

	// the moves sent by player will reach this method. it sends them to the
	// correct table
	public StringBuffer rummyMove(Player.Presence p, String movedet) {
		int tid = getTID(movedet);// table id, which table he wants to be seated

		StringBuffer buf = new StringBuffer();// for response back to player
		_cat.debug("Rummy21Server game - move req --" + movedet);

		if (movedet == null || movedet.equals("null")) {
			buf.append("Rummy21Server=Rummy21Server,grid=")
					.append(-1)
					.append(",MsgDropped=MsgDropped,player-details=" + p.name()
							+ "|" + p.netWorth());
			return buf;
		}
		if (tid >= MAX_TABLES) {
			buf.append("Rummy21Server=Rummy21Server,grid=")
					.append(-1)
					.append(",MsgDropped=InvalidTable,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}

		// send this message to the table
		p.setLatestMoveStr(movedet);
		add(tid, p);
		return null;
	}

	public StringBuffer Rummy21TablesList(Player.Presence p, String mdDet) {
		return gameDetail();
	}

	public void leave(Presence p) {
		int tid = p.getRUMMYTID();
		if (tid == -1)
			return;// no need to do anything, already cleared this player out

		// find if a corresponding rummy profile is present
		RummyProfile kp = _tables[tid].findProfile(p);
		if (kp == null) {
			int obs = _tables[tid].findObsPos(p);
			if (obs > -1) {
				kp = _tables[tid].findObservor(obs);
				if (kp != null) {
					_tables[tid].removeObserver(kp);
				}
			}
			return;
		}

		if (_tables[tid].isLeft(kp)) {
			return;
		}
		
		if (kp._allotedCards == null){
			_cat.debug("never a part of the hand " + kp.pos());
			_tables[tid]._players[kp.pos()] = null;
		}
		else {
			// just mark the player as left. on completion of cycle, the player will
			// be removed
			kp.rummyLeftTableTime = System.currentTimeMillis();
			kp.setRUMMYMovesStr("&Leave");
			kp.setRUMMYMoveId(move_RUMMY_LEFT);
			kp.setRUMMYStatus(status_REMOVED + status_FOLDED);
			
			_tables[tid].handleMoveNoAction(kp.pos());
			_cat.debug("take action plyr left : " + kp.pos()
					+ " from table : " + tid);
			kp.setRUMMYStatus(status_LEFT);
		}
	}

}
