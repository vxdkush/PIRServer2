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
import com.hongkong.game.resp.Response;
import com.hongkong.game.util.Card;
import com.hongkong.game.util.Cards;
import com.hongkong.nio.Client;
import com.hongkong.nio.Handler;
import com.hongkong.server.GamePlayer;
import com.hongkong.server.GameProcessor;
import com.atlantis.util.Base64;

public class Rummy500Server extends CasinoGame {
	static Logger _cat = Logger.getLogger(Rummy500Server.class.getName());
	String _name;
	double _minBet;
	double _maxBet;
	double totalBet = 0, totalWin = 0, totalGames = 0;
	String moveDetails = "";
	
	int globalIndex = 0;

	public static final int MAX_PLAYERS = 6;
	public static final int move_RUMMY_INVALID = 0;
	public static final int move_RUMMY_MELDNEW = 1;
	public static final int move_RUMMY_MELDEXIST = 2;
	public static final int move_RUMMY_NEWCARD = 4;
	public static final int move_RUMMY_DISCARD = 8;
	public static final int move_RUMMY_LEFT = 64;
	public static final int move_RUMMY_JOINED = 128;
	public static final int move_RUMMY_CHAT = 256;
	
	public static final int move_RUMMY_SITOUT = 512;
	public static final int move_RUMMY_SITIN = 1024;

	public int globalPenal = 0;
	public boolean globalFirstLife = false, globalSecondLife = false;

	public static final int status_NONE = 1;
	public static final int status_ACTIVE = 2;
	public static final int status_LEFT = 4;
	public static final int status_NEXTGAMEJOIN = 8;
	public static final int status_SITTINGOUT = 16;

	public static final int MAX_TABLES = 10;

	Rummy500Table[] _tables = null;

	boolean _keepServicing = false;

	private LinkedList[] _msgQ = new LinkedList[MAX_TABLES];

	public void add(int index, Presence cmdStr) {
		(_msgQ[index]).add(cmdStr);
	}

	public Presence fetch(int index) {
		return (Presence) (_msgQ[index].removeFirst());
	}

	public Rummy500Server(String name, double minB, double maxB, GameType type,
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
		_tables = new Rummy500Table[MAX_TABLES];
		for (int i = 0; i < MAX_TABLES; i++) {
			_tables[i] = new Rummy500Table();
			_tables[i].validTable = true;
			_tables[i].POINTSPERRUPEE = 1.0;
			Thread t = new Thread(_tables[i]);
			t.setName("Rummy500-Table-" + i);
			t.setPriority(Thread.NORM_PRIORITY);
			_tables[i].setIndex(i);
			t.start();

			_cat.debug("starting 500 thread : " + i);
		}
	}

	public StringBuffer gameDetail() {
		StringBuffer sb;
		sb = new StringBuffer("Rummy500Server=Rummy500Server")
				.append(",min-bet=").append(_minBet).append(",max-bet=")
				.append(_maxBet).append(",Rummy500Tables=");
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
		
		public Card[] _meldedCards = null;
		public int _indexMeldedCards = 0;
		
		public int totalScore = 0;
		
		public int totalPtsPaidToWinner = 0;
		
		public int sumTotalWinPts = 0;

		public String cardsStrFromClient = "";

		public int fixPosCard = -1;
		
		public boolean newCardTaken;
		public boolean discardCardTaken;
	}

	class Rummy500Table implements Runnable {
		volatile boolean _gameOngoing = false;
		double _rake;
		static final double RAKE_PERCENTAGE = 5;
		double POINTSPERRUPEE = 0.5;
		volatile RummyProfile[] _players = new RummyProfile[MAX_PLAYERS];
		// all the players who paid ante or who opted out of ante only - for
		// entries in grs table
		int _nextMovesAllowed;// to remember the moves that are allowed
		volatile int _nextMovePlayer = -1;
		volatile int _dealer;// to mark the very first player
		volatile int _rummyPlayer;// first one to make a move.
		int _winnerPos = -1;
		
		String _amtWonString = "";
		
		volatile boolean roundStarted = false;

		boolean fixingDealerOngoing = false;
//		boolean fixingDealerNextHand = true;
		int countHandsAfterDealerFix = 0;
		
		int errorCode = -1;

		String _drawString = "";
		String _currRoundResStr;
		
		String _roundWinnerName;
		
		double dbwinamt = 0;

		String newCardAdded = "";

		String _cardDealingOrder = "";

		// the card for which the players wage war
		Card _discardCard;//, _firstCard;

		boolean validTable = false;

		BitSet _deck = new BitSet(); // this is for fresh pile
		Card[] _discardDeck = new Card[65]; // when ever a card is discarded, we
											// add it here. when one is removed,
											// prev card comes to fore.
		// when the fresh pile runs out, we unset the bits in _desk that are set
		// in _discardDeck and empty out _discardDeck
		int _indexDiscardDeck;
		
		//for jokers. 2 per deck
		int totalJokersAlreadyDealt = 0;
		
		String[] _allMeldedCards = null;
		int _indexAllMeldedCards = 0;
		String[] _allJokersDesc = null;

		int NUM_DECKS = 1;
		int MAX_CARDS = 52 * NUM_DECKS - 1;
		
		int maxCardsToPlay;

		int roundNo = 0;

		int _lastMove;
		int _lastMovePos = -1;
		String _lastMoveString = "";
		
		String[] names = new String[MAX_PLAYERS];
		int countNames = 0;

		Vector _observers = new Vector();

		long rummygrid;
		Calendar _gameStartTime;

		long _nextMoveExpTime;
		boolean noMovesToBeAccepted = false;
		
		int counterGameNotStarted = 0;

		int _tid = -1;

		volatile long lastRespTime = 0;
		
		String chatMessage = "";
		int chatOn = 0;
		
		//for sitting out players
		ArrayList<Integer> sitoutPlayers = new ArrayList<Integer>();
		ArrayList<Integer> sitinPlayers = new ArrayList<Integer>();

		public Rummy500Table() {
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
					_cat.debug("name : " + _players[k]);
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

		public void removeFromTable(int index) {
			// for (int k = 0; k < MAX_PLAYERS; k++) {
			// if (_players[k] != null
			// && _players[k].getName().compareToIgnoreCase(
			// p.getName()) == 0) {
			// // found the offending player
			// _players[k].rummyLeftTableTime = -1;
			// _players[k] = null;
			// _cat.debug("kicked out .... from table : " + _tid);
			// break;
			// }
			// }

			if (_players[index] != null) {
				_players[index].rummyLeftTableTime = System.currentTimeMillis();
				// _players[index] = null; //can't make it null
				_players[index].setRUMMYStatus(status_LEFT);
				_players[index].setRUMMYMoveId(move_RUMMY_LEFT);
			}
		}

		public void resetTable() {
			//find a friend feature - clear out here
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null) {
					_players[m].getPresence().player().setActiveGame(-1);
				}
			}
			
			if(!_gameOngoing){
				//for supporting sitout and sitin. 
				//sitout means next game he wont join
				//sitin means next game he will be joining
				for (int m = 0; m < sitoutPlayers.size(); m++){
					int pos = sitoutPlayers.get(m);
					if (_players[pos] != null && !isLeft(_players[pos])){
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
			// first cull the players - this will leave some open seats
			_cat.debug("from reset table and winner pos is " + _winnerPos);
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null) {
					if (isSittingOut(_players[m])
					// ||!_players[m].getRUMMYBetApplied() //this condition is
					// to throw out the players who just sit on table
					) {
						_players[m].clearRUMMYMovesStr();
						_players[m].setReloadReq(false);
						_players[m]._allotedCards = null;
						_players[m]._firstMoveDone = false;
						_players[m].cardsStrFromClient = "";
						
						_players[m]._meldedCards = null;
						_players[m]._indexMeldedCards = 0;
						
					} else {
						
						if (isToJoinNextGame(_players[m]) && _winnerPos == -1){
							continue;//game not over yet. can't join now.
						}
						
						_players[m].clearRUMMYMovesStr();
						_players[m].setReloadReq(false);
						_players[m]._allotedCards = null;
						_players[m]._firstMoveDone = false;
						_players[m].setRUMMYStatus(status_ACTIVE);
						_players[m].rummyLastMoveTime = System
								.currentTimeMillis();
						_players[m].cardsStrFromClient = "";
						_players[m].getPresence().resetRoundBet();
						
						_players[m]._meldedCards = null;
						_players[m]._indexMeldedCards = 0;
					}
				}
			}
			
//			if (_winnerPos != -1){
//				_drawString = "";
//				_cat.debug("clearing out drawsting here 1111111111111111111111111111111111111111111111111111111111111111111");
//			}
			_winnerPos = -1;

			//find a friend feature
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null && !isLeft(_players[m])) {
					_players[m].getPresence().player().setActiveGame(6);
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
			
			errorCode = -1;
			
			_currRoundResStr = "";
			rummygrid = setNextGameRunId();
			_nextMovesAllowed = move_RUMMY_NEWCARD |  move_RUMMY_MELDEXIST
					| move_RUMMY_MELDNEW;;
			_roundWinnerName = "";

			_nextMoveExpTime = System.currentTimeMillis();

			_deck.clear();
			_discardDeck = new Card[65];
			_indexDiscardDeck = 0;
			
			totalJokersAlreadyDealt = -1;

			newCardAdded = "";

			_lastMovePos = -1;
			_lastMove = 0;
			_lastMoveString = "";

//			_firstCard = new Card(drawCard());
//			_firstCard.setIsOpened(true);
			
			//15 is the max no of melds that i think is possible.
			_allMeldedCards = new String[100];
			
			_allJokersDesc = new String[15];
			for (int k = 0; k < 15; k++)
				_allJokersDesc[k] = "";
			
			_indexAllMeldedCards = 0;
			
			removeObservorsTurnedPlayers();
		}
		
		private void removeObservorsTurnedPlayers(){
			for (int i = 0; i < _players.length; i++){
				if (_players[i] != null){
					//check if this player is in observors list. if so, remove it
					for (int k = 0; k < _observers.size(); k++){
						RummyProfile rp = (RummyProfile) _observers.get(k);
						if (rp.name.compareToIgnoreCase(_players[i].getName()) == 0){
							//found it. remove it
							_observers.remove(k);
							break;
						}
					}
				}
			}
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

		public synchronized void handleGameJoinReq(int obspos, String name) {
			// get the player
			RummyProfile p = null;
			boolean rejoinReq = false;

			// check if the player was playing before he decided to logout, now
			// he wants to come back
			_cat.debug("inside handlegamejoinreq.... " + name);
			
			if (!name.isEmpty()) {
				_cat.debug("name of player asking to join : " + name);
				p = findProfile(name);
				_cat.debug("after findprofile");
				if (p != null) {
					rejoinReq = true;
					_cat.debug("p not null");
				}
				else {
					_cat.debug("p is null");
					p = findObservor(name);
					_cat.debug("findobservoer after call.");
				}
			}
			else {
				_cat.debug("name is empty. calling finobser");
				p = findObservor(obspos);
				_cat.debug("after findobservor");
			}

			if (p == null) {
				_cat.debug("can't find the player in observors list or old players : "
								+ obspos);
				return;
			}
			
			_cat.debug("found p");

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
				if (fixingDealerOngoing	|| _gameOngoing || _countPlayersInit >= 2) {

					pos = adjustNextToDealer(p);
					if (pos == -1) {
						_cat.debug("despite attempts no avail pos at table :"
										+ _tid + " for player : " + p.getName());
						return;
					}
					
					chatOn = 0;
					chatMessage = p.name + " at pos : " + (pos + 1) + " joined.";
					
					_players[pos].getPresence().player().setActiveGame(6);
					_players[pos].getPresence().player().setActiveTable(_tid);
					
					_players[pos].setTimeBank(30000);
					
					_cat.debug("roundStarted : " + roundStarted + " , status of game method : " + isGameAlreadyStarted());

					//if game not started, deal this player in. else make him join next game. not next hand. next game.
					if (!roundStarted) {
						//player might try to join at the time when server is still settling the previous round
						if (!isGameAlreadyStarted())
							p.setRUMMYStatus(status_ACTIVE);
						else
							p.setRUMMYStatus(status_NEXTGAMEJOIN);
					}
					else {
						p.setRUMMYStatus(status_NEXTGAMEJOIN);
					}
					
				} else {
					// less than 2 players - call resettable once
					resetTable();
					_countPlayersInit = getCountTotalPlayers();
					_cat.debug("total count plyers is " + _countPlayersInit);

					if (_countPlayersInit == 0) {
						pos = 0;// very first player
						_players[0] = p;
						_nextMoveExpTime = System.currentTimeMillis();
						clearImpVars(); //clear these variables here. let us see if this solves our problem.
					} else if (_countPlayersInit == 1) {
						//check if the already seated plaeyr is on pos 1.
						//if so, give pos 0 to the incoming player
						//after all, there is only 1 player on table. 0 is empty
						if (_players[1] != null)
							pos = 0;
						else
							pos = 1;
						_players[pos] = p;
						clearImpVars();
						_nextMoveExpTime = System.currentTimeMillis();
					}
					
					chatOn = 0;
					chatMessage = p.name + " at pos : " + (pos + 1) + " joined.";
					
					_players[pos].getPresence().player().setActiveGame(6);
					_players[pos].getPresence().player().setActiveTable(_tid);
					
					_players[pos].setTimeBank(30000);
					
					p.setRUMMYStatus(status_ACTIVE);// the first 2 players have
													// to be marked active
				}
			} else {
				// a player can't join back immediately on the same table he
				// left. it could be a ploy to cheat the house
				// TBD - check if currently playing players list has this name.
				// if so, then kick him out
				// else allow him
				pos = p._pos;
				p.setRUMMYStatus(status_NEXTGAMEJOIN);
				_cat.debug("returning player joining at " + pos);
				chatOn = 0;
				chatMessage = p.name + " at pos : " + (pos + 1) + " rejoioned.";
				
				_players[pos].getPresence().player().setActiveGame(6);
				_players[pos].getPresence().player().setActiveTable(_tid);
				_players[pos].setTimeBank(30000);
			}

			removeObserver(p);

			// now make him seat on the table on that position
//			p.setPresence(pold);
			_cat.debug("pos of palyer is : " + pos);
			p.setPos(pos);
			p.setRummyPlayer();
			p.getPresence().setRUMMYTID(_tid);
			p.getPresence().lastMove(Moves.RUMMY_SITIN);
			p.setRUMMYMoveId(0);

			addPresence(p);
			_cat.debug("Rummy500Server game - sit in req buf -- on table : "
							+ _tid);
			// send message to all players about the new player
			_lastMove = move_RUMMY_JOINED;
			_lastMovePos = pos;
			_lastMoveString = "Joined";

			p.rummyLeftTableTime = -1;
			p.rummyLastMoveTime = System.currentTimeMillis();
			
			_nextMoveExpTime = System.currentTimeMillis();

			int countPlayers = getCountActivePlayers();
			if (!_gameOngoing) {
				_winnerPos = -1;
				broadcastMessage(-1);
			}
//			else if (countPlayers == 2 && !_gameOngoing) {
//				_winnerPos = -1;
//				// fixing dealer process already on, then no need to start it
//				// again
//				if (!fixingDealerOngoing) {
//					fixingDealerNextHand = true;
//					fixDealerFirst();
//				}
//			} 
			else {
				// not calling start game from here - that has to be done from
				// run method
//				fixingDealerNextHand = true;
//				p.setRUMMYStatus(status_NEXTGAMEJOIN);
				broadcastMessage(-1);
			}
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

		public boolean isToJoinNextGame(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_NEXTGAMEJOIN) > 0){
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

		public boolean isSittingOut(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_SITTINGOUT) > 0) {
				return true;
			}
			return false;
		}

		public boolean isGameAlreadyStarted(){
			_cat.debug("isgamealready started method");
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null && !isLeft(_players[i])
						&& _players[i].totalScore > 0)
					return true;
			}

			return false;
		}

		public int getCountActivePlayers() {
			int cnt = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null 
						//&& !isLeft(_players[i])
						&&
						// !isFolded(_players[i]) &&
						!isToJoinNextGame(_players[i]) &&
						!isSittingOut(_players[i]) //removed !isRemoved(_players[i]) &&  condition.
						)
					cnt++;
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
		
		public int getCntActiveNotLeft(){
			int cnt = 0;
			for (int m = 0; m < MAX_PLAYERS; m++){
				if (_players[m] != null && !isSittingOut(_players[m])
						&& !isToJoinNextGame(_players[m])
						&& _players[m].rummyLeftTableTime == -1
						){
					cnt++;
					_cat.debug("getcntnotleft -- num active : " + cnt + " latest : " + m);
				}
			}
			
			return cnt;
		}

		public int getCountTotalPlayers() {
			int cnt = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null && !isLeft(_players[i]))
					cnt++;
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
			while ((_players[pos1].getRUMMYStatus() & status_SITTINGOUT) > 0
					|| (_players[pos1].getRUMMYStatus() & status_NEXTGAMEJOIN) > 0
					|| (_players[pos1].getRUMMYStatus() & status_LEFT) > 0) {
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
			while ((_players[pos1].getRUMMYStatus() & status_NEXTGAMEJOIN) > 0
					|| (_players[pos1].getRUMMYStatus() & status_SITTINGOUT) > 0
					|| (_players[pos1].getRUMMYStatus() & status_LEFT) > 0) {
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

			// always find the new dealer if player is leaving a table
//			fixingDealerNextHand = true;
			_lastMovePos = pos;
			_lastMoveString = "Left";
			
			chatOn = 0;
			chatMessage = _players[pos].name + " at pos : " + (pos + 1) + " left.";

			if (_nextMovePlayer != 111) {
				errorCode = 5;
				
				if (pos == _nextMovePlayer) {
					// same action as that of player leaving table
					handleTimedOut(true);
					return;
				}

				if (!checkGameOver()) {
					errorCode = -1;
					broadcastMessage(-1);
				}
			}
		}

		private void handleTimedOut(boolean left) {
			_lastMovePos = _nextMovePlayer;
			_lastMoveString = "FoldedDueToInaction";
			chatMessage = _players[_nextMovePlayer].name + " at pos : " + (_nextMovePlayer + 1) + " timed out";
			
			if (!left && _players[_nextMovePlayer].isEligibleTimeBank()) {
				_players[_nextMovePlayer].setRUMMYMovesStr("&TimedOut");
				_players[_nextMovePlayer].setRUMMYStatus(status_LEFT);
			}
			else {
				//mark the player as left for he has now exhausted time bank
				_players[_nextMovePlayer].setRUMMYMovesStr("&FoldedLeft");
				if (left) {
					_players[_nextMovePlayer].setRUMMYMovesStr("&Left");
					_lastMoveString = "Left";
					chatMessage = _players[_nextMovePlayer].name + " at pos : " + (_nextMovePlayer + 1) + " left";
				}
				
				_players[_nextMovePlayer].setRUMMYStatus(status_LEFT);
				_players[_nextMovePlayer].rummyLeftTableTime = System.currentTimeMillis();
				_players[_nextMovePlayer].setTimeBank(-1);
				_players[_nextMovePlayer].setUsingTimeBank(false);
			}
			_nextMoveExpTime = System.currentTimeMillis();
			
			chatOn = 0;
			
			
			if (!checkGameOver()) {
			
//				removePlayersDisconnected();
				//find the next move player. send message to all players
				_nextMovePlayer = getNextActivePos(_nextMovePlayer);
				_nextMovesAllowed = move_RUMMY_NEWCARD |  move_RUMMY_MELDEXIST
						| move_RUMMY_MELDNEW;
				broadcastMessage(-1);
			}
		}

		// to be called after each round is over. if posWinner is -1, it means
		// that no winner is there
		public void declareRoundOver(int pos) {
			_cat.debug("entering declare round over");
			_nextMoveExpTime = System.currentTimeMillis();
			
			roundStarted = false;
			
			String holdStrEarnedVal = "";
			
			//for each player, compute the points
			int highestScore = 0, highestScorer = -1;
			for (RummyProfile rp : _players){
				//check had  && isActive(rp) first. now removed for removed players. hah! beat that!
				if (rp != null && rp.getPresence() != null && !isToJoinNextGame(rp) && !isSittingOut(rp)) {
					int valEarned = computePoints(rp._pos);
					
					if (valEarned > highestScore){
						highestScore = valEarned;
						highestScorer = rp._pos;
					}
					
					rp.totalScore += valEarned;
					
					holdStrEarnedVal += rp.pos() + "`" + valEarned + "|";
					
					rp.setRUMMYMovesStr("&RoundValue^" + valEarned);
				}
			}
			
			if (highestScorer != -1){
				//round is over so whip up some string tags
				_roundWinnerName = _players[highestScorer].getName();
			}
			else if (pos != -1) {
				//round is over so whip up some string tags
				_roundWinnerName = _players[pos].getName();
			}
			
			if (!holdStrEarnedVal.isEmpty())
				holdStrEarnedVal = holdStrEarnedVal.substring(0, holdStrEarnedVal.length() - 1);
			
			_currRoundResStr = holdStrEarnedVal;
			if (!_drawString.isEmpty())
				_drawString += "#" + _currRoundResStr;
			else
				_drawString = _currRoundResStr;
			
			// now take this opportunity to find out if the game is over. this
			// will happen the moment a player's total points go beyond 500
			boolean gameOverFlag = false;
			_winnerPos = -1;
			int winnerPts = 0;
			for (RummyProfile rp : _players){
				if (rp == null || rp.getPresence() == null || isSittingOut(rp) || isToJoinNextGame(rp))
					continue;
				
				if (rp.totalScore >= POINTS_TO_END_GAME){
					if (!gameOverFlag){
						gameOverFlag = true;
						_winnerPos = rp._pos;
						winnerPts = rp.totalScore;
					}
					else {
						//oh no. more than one player has scored more than 500. compare their score. the higher value wins.
						//in case of tie, let the game continue.
						if (winnerPts < rp.totalScore){
							winnerPts = rp.totalScore;
							_winnerPos = rp._pos;
						}
						else if (winnerPts == rp.totalScore){
							//let them play. next hand it will be decided.
							gameOverFlag = false;
						}
					}
				}
				
				if (isLeft(rp)){
					rp._allotedCards = null;
				}
			}
			
			//Bugs related to player leaving table and tables not getting cleared
			//also bugs related to player leaving and getting results long after they have left
			if (!gameOverFlag){
				_cat.debug("checking if only one player is there " + _tid);
				int count = getCntActiveNotLeft();
				_cat.debug("cntactivenotleft returns : " + count);
				if (count == 1){
					gameOverFlag = true;
					_winnerPos = getOnlyActivePos();
				}
				else if (count <= 0){
					_cat.debug("horrible! no player on table!");
					_nextMovePlayer = -1;
					broadcastMessage(pos);
					_gameOngoing = false;
					clearImpVars();
					_cat.debug("clearing out drawsting here 3333333333333333333333333333333333333333");
					
					for (int k = 0; k < MAX_PLAYERS; k++){
						_players[k] = null;
					}
				}
			}
			
			if (gameOverFlag) {
				//winner gets the sum of difference of his points and the other player's points for all players
				winnerPts = _players[_winnerPos].totalScore;
				
				String winResult = "";
				
				for (RummyProfile rp : _players){
					if (rp == null || rp.getPresence() == null || isToJoinNextGame(rp) || isSittingOut(rp)) // || !isActive(rp)
						continue;
					
					if (rp._pos != _winnerPos){
						rp.totalPtsPaidToWinner = winnerPts - rp.totalScore;
						_players[_winnerPos].sumTotalWinPts += rp.totalPtsPaidToWinner;
						//deduct bet amt
						
						rp.getPresence().currentRoundBet(rp.totalPtsPaidToWinner * POINTSPERRUPEE);
						winResult += rp._pos + "`" + (rp.totalPtsPaidToWinner * -1) + "|";
						
						rp.setRUMMYMovesStr("GamePenalty^" + rp.totalPtsPaidToWinner);
						
						_amtWonString += rp._pos + "'" + ((rp.totalPtsPaidToWinner * POINTSPERRUPEE) * -1) + "|";
					}
				}
				
				_players[_winnerPos].setRUMMYMovesStr("GameWinPts^" + _players[_winnerPos].sumTotalWinPts);
				winResult += _winnerPos + "`" + _players[_winnerPos].sumTotalWinPts;
				_drawString += "^" + winResult;
				
				// game over, give win amt to winner
				double winamt = _players[_winnerPos].sumTotalWinPts * POINTSPERRUPEE;
				_rake = winamt * RAKE_PERCENTAGE / 100;
				winamt -= _rake;
				_players[_winnerPos].getPresence().addToWin(winamt);
				
				_amtWonString += _winnerPos + "'" + winamt;

				declareGameOver(_winnerPos, winamt);
			}
			
			//have to turn it off
//			_gameOngoing = false;
			_nextMovePlayer = -1;
			broadcastMessage(pos);
			
			try {
				Thread.sleep(7000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			boolean oneMoreMsg = false;
			if (gameOverFlag){
				_gameOngoing = false;
				_winnerPos = -1;
				_drawString = "";
				
				_cat.debug("clearing out drawsting here 3333333333333333333333333333333333333333");
				
				for (int k = 0; k < MAX_PLAYERS; k++){
					if (_players[k] != null && _players[k].rummyLeftTableTime != -1){
						sendMessageKickedOut(k, 0);
						_players[k] = null;
						oneMoreMsg = true;
					}
				}
			}
			
			if (oneMoreMsg){
				broadcastMessage(-1);
			}
		}

		// to be called when game is well and truly over -- when plyr count < 2
		// and when one player's win amt >= 100
		public void declareGameOver(int posWinner, double winamt) {
			_nextMoveExpTime = System.currentTimeMillis();
			_winnerPos = posWinner;
			roundNo = 0;
			
			_cat.debug("game over. db entry work here.");
			
			_players[_winnerPos].setRUMMYWinAmt(winamt);
			
			dbwinamt = winamt;
			
			new Thread(){
				public void run(){
					// loop thru the players, find the participating players
					// insert their entries in t_player_per_grs
					GameRunSession grs = new GameRunSession(_gid, rummygrid,GameType.Rummy500);//65536 for rummy 500
//							_type.intVal());
					grs.setEndTime(new Date());
					grs.setStartTime(_gameStartTime.getTime());
					double rake[];
					rake = Utils.integralDivide(_rake, getCountActivePlayers());

					// ************STARTING BATCH
					int j = -1;
					try {
						Statement grs_stat = grs.startBatch();
						for (int i = 0; i < MAX_PLAYERS; i++) {
							// _playersInHand
							if (_players[i] == null || isSittingOut(_players[i]) || isToJoinNextGame(_players[i])) // || !isActive(_players[i])
								continue;

							RummyProfile pr = _players[i];
							j++;

							GamePlayer gp = (GamePlayer) pr.getPresence().player();
							grs.setDisplayName(gp.name());
							grs.setPosition(pr.pos());
							grs.setPot(dbwinamt + _rake);
							double betamnt = pr.getPresence().currentRoundBet();
							grs.setStartWorth(pr.getGameStartWorth());

							grs.setWinAmount(0);
							// for winner only these 2 lines
							if (i == _winnerPos) {
								// win amt will be actual win amt after accounting for
								// bet amt
								grs.setWinAmount(dbwinamt);
								grs.setEndWorth(pr.getGameStartWorth() + dbwinamt
										- betamnt);
							} else {
								grs.setEndWorth(pr.getGameStartWorth() - betamnt);
							}
							grs.setBetAmt(betamnt);
							// now for all
							grs.setSessionId(gp.session());
							grs.setRake(rake[j]);

							_cat.debug("string to be added to server : " + pr.getRUMMYMovesStr());
							grs.setMoveDet(pr.getRUMMYMovesStr());
							grs.save(grs_stat);

							// now set the player's start worth
							_players[i].setGameStartWorth(grs.getEndWorth());
						}
						// COMMITTING BATCH
						grs.commitBatch(grs_stat);
						_cat.debug("grs committed...");
						// TBD- lo500 session have to be updated with win and bet
					} catch (Exception ex) {
						_cat.debug("Exception - " + ex);
						_cat.debug("exception : " + ex.getStackTrace());
					}

				}
			}.start();
			
			//for t_user_eap, this is the only place
			new Thread(){
				public void run(){
					double rake[];
					rake = Utils.integralDivide(_rake, countNames);
					
					for (int m = 0; m < countNames; m++){
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

		/*void addCardsToFreshPile(Card[] crs, int index) {
			if (crs == null)
				return;

			for (int i = 0; i < index; i++) {
				if (crs[i].getIndex() > -1) {
					_deck.clear(crs[i].getIndex());
				}
			}
		} */

		boolean checkGameOver() {
			_cat.debug("check game over");
			//we check if a player has run out with all his cards.
			if (_gameOngoing) {
				for (int i = 0; i < MAX_PLAYERS; i++){
					RummyProfile rp = (RummyProfile) _players[i];
					
					if (rp != null && rp.getPresence() != null && isActive(rp) && (rp._allotedCards == null || rp._allotedCards.length == 0)){
						_cat.debug("game over. cards are done and dusted.");
						_cat.debug("and winnerrrr issssssssssssss " + i);
						declareRoundOver(i);
						return true;
					}
				}
			}
			
//			//error checking --- first check if the fresh card pile is still alive.
//			if (_deck.cardinality() > (MAX_CARDS + 2 * NUM_DECKS)) {//should be max_cards - 2 but for testing letting it to be max_cards itself
//				System.out
//						.println("WARNING!!!! fresh pile has run out!!!!");
//				declareRoundOver(-1);// -1 will force declareroundover
//				return true;
//			}
			
			int cntActPlyrs = getCntStatusActivePlayers();
			if (cntActPlyrs < 2) {
				// game can't proceed, the lone ranger is the winner
				if (cntActPlyrs >= 1) {
					int pos = getOnlyActivePos();
					if (_players[pos].getUsingTimeBank()){
						_players[pos].setTimeBank(_players[pos].getTimeBankExpTime() - System.currentTimeMillis());
						_players[pos].setUsingTimeBank(false);
					}
					
					declareRoundOver(pos);
					return true;
//					if (pos != -1 && _gameOngoing) {
//						
//						if (errorCode <= 0)
//							errorCode = 4;
//						
//						//check if even a single meld has been created. else it is just hogwash. no game was ever started. reset table. end game.
//						if (_indexAllMeldedCards <= 0){
//							resetTable();
//							
//							_cat.debug("ending game for only one plaeyr is there and no game was possible");
//							_gameOngoing = false;
//						}
//
//						// only one player, so end the entire game here
//						//find the winner. it is the player with highest totalscore
//						_winnerPos = -1;
//						double winPts = 0;
//						for (RummyProfile rp : _players){
//							if (rp == null || rp.getPresence() == null || isToJoinNextGame(rp) 
//									|| isSittingOut(rp)
//									)
//								continue;
//							
//							if (_winnerPos == -1){
//								_winnerPos = rp.pos();
//								winPts = rp.totalScore;
//							}
//							else {
//								if (rp.totalScore > winPts){
//									_winnerPos = rp.pos();
//									winPts = rp.totalScore;
//								}
//							}
//						}
//						
//						//what if _winnerPos gets pos of sole survivor and yet the win pts are < 0 for this player has lost?
//						//TBD
//						
//						//winner gets the sum of difference of his points and the other player's points for all players
//						int winnerPts = _players[_winnerPos].totalScore;
//						
//						String winResult = "";
//						
//						for (RummyProfile rp : _players){
//							if (rp == null || rp.getPresence() == null || isToJoinNextGame(rp))
//								continue;
//							
//							_cat.debug("pos " + rp._pos + " has total score : " + rp.totalScore + " and totalptswinner : " + rp.totalPtsPaidToWinner);
//							if (rp._pos != _winnerPos){
//								rp.totalPtsPaidToWinner = winnerPts - rp.totalScore;
//								_players[_winnerPos].sumTotalWinPts += rp.totalPtsPaidToWinner;
//								//deduct bet amt
//								
//								rp.getPresence().currentRoundBet(rp.totalPtsPaidToWinner * POINTSPERRUPEE);
//								winResult += rp._pos + "`" + (rp.totalPtsPaidToWinner * -1) + "|";
//								
//								rp.setRUMMYMovesStr("GamePenalty^" + rp.totalPtsPaidToWinner);
//							}
//						}
//						
//						_players[_winnerPos].setRUMMYMovesStr("GameWinPts^" + _players[_winnerPos].sumTotalWinPts);
//						winResult += _winnerPos + "`" + _players[_winnerPos].sumTotalWinPts;
//						_drawString += "^" + winResult;
//						_cat.debug("winresult : " + winResult + " for winner : " + _winnerPos);
//						
//						// game over, give win amt to winner
//						double winamt = _players[_winnerPos].sumTotalWinPts * POINTSPERRUPEE;
//						_rake = winamt * RAKE_PERCENTAGE / 100;
//						winamt -= _rake;
//						_players[_winnerPos].getPresence().addToWin(winamt);
//
//						declareGameOver(_winnerPos, _players[_winnerPos].sumTotalWinPts, winamt);
//						
//						broadcastMessage(pos);
//						
//						clearImpVars();
//						_cat.debug("clearing out drawsting here 2222222222222222222222222222222222222222222222222222222222");
//					}
				} 
//
				return true;
			} else {
				return false;
			}
		}
		
		void clearImpVars(){
			_deck.clear();
			_indexDiscardDeck = 0;
			_indexAllMeldedCards = 0;
			
			_drawString = "";
			roundStarted = false;
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null) {
					_players[m].totalPtsPaidToWinner = 0;
					_players[m].totalScore = 0;
					_players[m].sumTotalWinPts = 0;
				}
				else {
					_players[m] = null;
				}
			}
		}

		void removePlayersDisconnected() {
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null) {
					if (isLeft(_players[i])) {
						sendMessageKickedOut(_players[i].pos(), 0);
						_players[i] = null;
					}
				}
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
		boolean determineIfJoker(Card crd) {
			if (crd.getIndex() >= 156)
				return true;

			return false;
		}

		boolean CheckIfSets(String[] toks2) {
			_cat.debug("checking sets with joker or no joker");
			int jokerCount = 0, playerCount = 0;
			Card[] playerCards = new Card[4];

			for (int i = 0; i < toks2.length; i++) {
				Card cr = new Card(toks2[i]);
				
				if (cr == null || cr.getIndex() == -1)
					return false;
				
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
						_cat.debug("set : " + playerCards[0] + " , "
								+ playerCards[1]);
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
						_cat.debug("set : " + playerCards[0] + " , "
								+ playerCards[1]);
						if (playerCards[0].getRank() == playerCards[1]
								.getRank()
								&& playerCards[0].getIndex() != playerCards[1]
										.getIndex()) {
							// it is a set - don't want dublee here
							return true;
						}
					} else if (playerCount == 3) {// it is a 4 card set with one
													// joker
						_cat.debug("set : " + playerCards[0] + " , "
								+ playerCards[1] + " , " + playerCards[2]);
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
					_cat.debug("set : " + playerCards[0] + " , "
							+ playerCards[1] + " , " + playerCards[2]);
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
						_cat.debug("set : " + playerCards[0] + " , "
								+ playerCards[1] + " , " + playerCards[2]
								+ " , " + playerCards[3]);
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
			_cat.debug("pure run checking");
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
				Card crd = new Card(toks2[index]);
				if (crd == null || crd.getIndex() == -1)
					return false;
				
				cardsArr.add(crd.getIndex());
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
			int be500Index = cardTwo[0];

			if (cardTwo[toks2.length - 1] % 13 == Card.ACE) {
				runpure = false;
			} else {
				for (int i = 1; i < toks2.length; i++) {
					if (be500Index + 1 != cardTwo[i]) {
						runpure = false;
						break;
					}
					be500Index = cardTwo[i];
				}
			}

			if (!runpure) {
				// check for the case of Ace, Two, Three et al. for these cases
				// as Ace is at the end the condition will fail
				if (cardTwo[toks2.length - 1] % 13 == Card.ACE) {
					runpure = true;
					_cat.debug("checknig pure run wiht Ace");
					be500Index = -1; // so that when you add 1 you get 0 which
										// is Card.TWO
					for (int i = 0; i < toks2.length - 1; i++) {
						if (be500Index + 1 != cardTwo[i] % 13) {
							runpure = false;
							break;
						}
						be500Index = cardTwo[i] % 13;
					}
				}
			}

			return runpure;
		}

	boolean CheckImPureRuns(String[] toks2) {
		 _cat.debug("impure run check");
		 int playerCount = 0, jokerCount = 0;
		 Card[] playerCards = new Card[toks2.length];
		
		 for (int i = 0; i < toks2.length; i++) {
			Card cr = new Card(toks2[i]);
			
			if (cr == null || cr.getIndex() == -1)
				return false;
			
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
		 int be500Index = cardTwo[0];
		 for (int i = 1; i < playerCount; i++) {
			 if (be500Index + 1 != cardTwo[i]) {
			 // we need joker or jokers to move from be500Index to
			 // cardTwo[i]
			 int value = cardTwo[i] - be500Index - 1;
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
			 be500Index = cardTwo[i];
			 }
		
		 	if (jokerNeeded <= jokerCount)
			 	return true;
			 else {
			 // there is a special case of Ace where it can be used with Two
			 // and Three and others to make a run
			 if (cardTwo[playerCount - 1] % 13 == Card.ACE) {
			 _cat.debug("checknig impure run wiht Ace");
			 // last card is an Ace - go for it
			 jokerNeeded = 0;
			 be500Index = -1;
			 for (int i = 0; i < playerCount - 1; i++) {
			 if (be500Index + 1 != cardTwo[i] % 13) {
			 // we need joker or jokers to move from be500Index
			 // to cardTWo[i]
			 int indCard = cardTwo[i] % 13;
			 int indCard2 = be500Index + 1;
			 int val = indCard - indCard2;
			 if (val < 0)
			 return false;
			
			 jokerNeeded += val;
			 }
			 be500Index = cardTwo[i] % 13;
			 }
			
			 if (jokerNeeded <= jokerCount)
			 return true;
			 else
			 return false;
			 }
			
			 return false;
			 }
		 }

		String countRunsAsArranged(String cardsstr) {
			int pureRuns = 0, impureRuns = 0, numSets = 0, tanalaCount = 0;
			String allRunsSetsGroup = "";
			
			cardsstr = cardsstr.trim();
			_cat.debug("meld to be tested : " + cardsstr);

			String[] toks1 = cardsstr.split("\\|");
			for (int i = 0; i < toks1.length; i++) {
				boolean somethingfound = false;
				_cat.debug("to check : " + toks1[i]);
				String[] toks2 = toks1[i].split("\\`");
				//get rid of white space
				for (int k = 0; k < toks2.length; k++){
					toks2[k] = toks2[k].trim();
				}
				
				//check here if we got 2 cards same. like Jc Jd Jd. this will fail pure and impure runs
				//but it won't fail condition for set.
				//this condition will work here because at max only 2 decks are to be used. no tanala here.
				for (int m = 0; m < toks2.length - 1; m++){
					for (int n = m+1; n < toks2.length; n++){
						if (toks2[m].compareToIgnoreCase(toks2[n]) == 0)
							return allRunsSetsGroup;
					}
				}
				
				// got the cards of one group
				// there can be these cases - a pure run, an impure run, a pure
				// set, an impure set, a tanala
				// if (toks2.length == 3) {
				// // could be a tanala
				// if (toks2[0].compareToIgnoreCase(toks2[1]) == 0
				// && toks2[0].compareToIgnoreCase(toks2[2]) == 0) {
				// tanalaCount++;
				// allRunsSetsGroup += i + "'";
				// somethingfound = true;
				// _cat.debug("tanala : " + toks1[i]);
				// }
				// }

				if (toks2.length >= 3 && !somethingfound) {
					// check for run
					if (CheckPureRuns(toks2)) {
						pureRuns++;
						allRunsSetsGroup += i + "'";
						somethingfound = true;
						_cat.debug("pure run : " + toks1[i]);
					}
					 else if (CheckImPureRuns(toks2)) {
						 impureRuns++;
						 allRunsSetsGroup += i + "'";
						 somethingfound = true;
						 _cat.debug("impure run : " + toks1[i]);
					 }

					if (!somethingfound
							&& (toks2.length == 3 || toks2.length == 4)) {
						// could be a set -. take care. there could be a joker
						// here
						if (CheckIfSets(toks2)) {
							numSets++;
							allRunsSetsGroup += i + "'";
							somethingfound = true;
							_cat.debug("set : " + toks1[i]);
						}
					}
				}
				_cat.debug(" , others group : " + allRunsSetsGroup);
			}

			if (!allRunsSetsGroup.isEmpty()) {
				allRunsSetsGroup = allRunsSetsGroup.substring(0,
						allRunsSetsGroup.length() - 1);
			}
			return allRunsSetsGroup;
		}

		int computePoints(int pos){
			//go thru the melded cards of this player and add their points
			int meldedPoints = computePoints2(_players[pos]._meldedCards);
			
			//whatever cards the players have will be penalized
			int penalty = computePoints2(_players[pos]._allotedCards);
			
			return meldedPoints - penalty;
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

				//we are using only 2 decks so it is ok. anything > 156 is a joker.
				//anything above 110 means Ace that has been used a meld Ace, 2, 3.
				if (cards[i].getIndex() >= 110 && cards[i].getIndex() < 156)
					dummy = 1;
				else {
					dummy = cards[i].getHighBJRank();
					if (dummy >= 11)
						dummy = 15;//aces have to be 15 except for when they are in meld Ace, Two, Three. Then Ace has got value of 1
					else if (dummy == 0)
						dummy = 15;
				}
			
				val += dummy;
				_cat.debug("card is " + cards[i] + ",dummy val : " + dummy + " total val : " + val);
			}

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

				// now check if someone has timed out his moves
				if (_gameOngoing && (System.currentTimeMillis() - _nextMoveExpTime) > 30000) { 
					noMovesToBeAccepted = true;
					errorCode = -1;
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

				// maintenance job - shall we create a separate thread for it?
				// maintenance job - if there is only one player on a table
				for (int m = 0; m < MAX_PLAYERS && _winnerPos != -1; m++) {
					if (_players[m] != null
							&& System.currentTimeMillis()
									- _players[m].rummyLeftTableTime > 1000 * 8
							&& (isLeft(_players[m]))) {
						// removed condition for waiting player to make a move -
						// that was creating issues with players who are waiting
						// to join next game
						// System.out
						// .println("nothing from this player, kick him out : "
						// + _players[m].getName());
						// before sending the message we should check if client
						// is showing the result window
						// if so, this message would curtail the beautiful,
						// rapturous experience of a winning player
						sendMessageKickedOut(m, 2);

						// remove the player
						_players[m] = null;
					}
				}

				// start new round after 5 seconds of result display
				if (_gameOngoing
						&& _nextMovePlayer == -1
						&& getCountActivePlayers() >= 2
						&& (System.currentTimeMillis() - _nextMoveExpTime) > 8000) {
					_cat.debug("from run starting new round");
					initGame();
					resetTable();
					startNewRound();
				}
				
				//error condition - for some reason a player left in the middle of game. end the game now.
				if (_gameOngoing
						&& _nextMovePlayer == -1
						&& getCountActivePlayers() < 2
//						&& !fixingDealerNextHand
						) {
					checkGameOver();
					initGame();
				}

				if (!_gameOngoing && _nextMoveExpTime != -1
						&& System.currentTimeMillis() - _nextMoveExpTime > 3000) {
					int countPlayers = getCountActivePlayers() + getCountSitInReqPlayers();
					if (getCountTotalPlayers() >= 2 && counterGameNotStarted < 6) {
						if (System.currentTimeMillis() - _nextMoveExpTime > 10000) {
							if (countPlayers >= 2){
								_cat.debug("from run fixing dealer first");
								fixDealerFirst();
							}
							else {
								counterGameNotStarted++;
								resetTable();
							}
						}
					} else {
						// clear out the jokers for it creates a wrong
						// impression
						_discardCard = null;
						_dealer = -1;
						_rummyPlayer = -1;
						clearImpVars();
						// remove the removed players now
						if (System.currentTimeMillis() - _nextMoveExpTime > 60000) { //orig was 20 seconds
							for (int m = 0; m < MAX_PLAYERS; m++) {
								if (_players[m] != null) {
									sendMessageKickedOut(m, 2);
									// remove the player
									removeFromTable(m);
//										_players[m] = null;
								}
							}
							_nextMoveExpTime = -1;
						}

					}
				}

				if (!_gameOngoing) {
					if (System.currentTimeMillis() - _nextMoveExpTime > 120000) {
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
			int k = Rng.nextIntBetween(0, 100);
			if (k == 73 || k == 69 || _deck.cardinality() >= 50) {
				// give a joker if it is possible
				if ((totalJokersAlreadyDealt+1) < NUM_DECKS * 2) {
					totalJokersAlreadyDealt++;
					_deck.set(161 + totalJokersAlreadyDealt);
					return 161 + totalJokersAlreadyDealt;
				}
			}
			
			int rand = Rng.nextIntBetween(0, MAX_CARDS);
			while (_deck.get(rand)) {
				rand = Rng.nextIntBetween(0, MAX_CARDS);
			}
			_deck.set(rand);
			
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
			// 0,-1,0,1436500.64,-1,-1
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
				// check here if game is in declare mode expecting losers to
				// send in their card string
				// if so, we can't let this player join the table - he/she has
				// to wait till dust settles
				if (_nextMovePlayer != 111 && _nextMovePlayer != 222)
					handleGameJoinReq(pos, p.name());
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
			if (_nextMovePlayer != 111 && pos != _nextMovePlayer && _nextMovePlayer != 222) {
				_cat.debug("no moves allowed from this pos!!!");
				sendErrorMessage(pos, 0);
				return;
			}

			// update the rummyLastMoveTime for this player
			_players[pos].rummyLastMoveTime = System.currentTimeMillis();

			if (moveId == move_RUMMY_NEWCARD) {
//				_nextMoveExpTime = System.currentTimeMillis();
				prp._firstMoveDone = true;
				_lastMove = move_RUMMY_NEWCARD;
				_lastMovePos = pos;
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);
				
				int typeO = 0;

				try {
					typeO = Integer.parseInt(type);
				} catch (NumberFormatException ex) {
					_cat.debug("wrong string for type!!!!!");
					sendErrorMessage(pos, 1);
					return;
				}
				
				if (_players[pos].newCardTaken || _players[pos].discardCardTaken){
					_cat.debug("can't give new card more than once");
					sendErrorMessage(pos, 0);
					return;
				}

				_cat.debug("reached here 3333333333333333333333333 "
						+ typeO + " , cards length : "
						+ prp._allotedCards.length + ", deck cardinality : " + _deck.cardinality());
				
				// if client sends this message again then 2nd and subsequent
				// message have to be dropped
//				if (prp._allotedCards.length >= 11) {
//					System.out
//							.println("already gave one card, how many does it want more !!!");
//					sendErrorMessage(pos, 0);
//					return;
//				}

				prp.setRUMMYMoveId(move_RUMMY_NEWCARD);
				prp.setRUMMYStatus(status_ACTIVE);
				_players[pos].rummyLeftTableTime = -1;
				// use type to find if it is frsh pile (-1) or index of cards from discard pile (starting from 0 which means only one card
				//type of 3 would mean 4 cards from discard pile, 0th, 1st,2nd and 3rd cards
				Card cr = null;
				if (typeO < 0) {
					_lastMoveString = "ChoseFreshCard";
					
					_players[pos].newCardTaken = true;
					
					//error checking --- first check if the fresh card pile is still alive.
					if (_deck.cardinality() >= (MAX_CARDS + NUM_DECKS  * 2)) {//should be max_cards - 2 but for testing letting it to be max_cards itself
						_lastMoveString = "NoFreshCard";
						_cat.debug("ERROR!!!! fresh pile has run out!!!!");
						declareRoundOver(-1);// -1 will force declareroundover
						return;
					}
					
					int randCard = drawCard();
					cr = new Card(randCard);
					cr.setIsOpened(true);
					prp.setRUMMYMovesStr("&GetFresh^" + cr.toString());
					newCardAdded = cr.toString();
					typeO = 0;
					
					_cat.debug("get new card : " + cr.toString());
					
					Card[] clonedCards = prp._allotedCards.clone();
					int size = clonedCards.length;
					prp._allotedCards = new Card[size + 1];
					for (int i = 0; i < size; i++) {
						prp._allotedCards[i] = clonedCards[i];
					}
					prp._allotedCards[size] = cr;
					
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " chose fresh card.";
					
				} else {
					
					//bit of error checking
					if (_indexDiscardDeck == 0 || _indexDiscardDeck < typeO){
						sendErrorMessage(pos, 4);
						return;
					}
					
					_lastMoveString = "ChoseDiscardedCardFromIndex";
					_lastMoveString += typeO;
					
					//allocate memory for all these cards
					Card[] clonedCards = prp._allotedCards.clone();
					int size = clonedCards.length;
					prp._allotedCards = new Card[size + typeO + 1];
					for (int i = 0; i < size; i++) {
						prp._allotedCards[i] = clonedCards[i];
					}
					
					int index = typeO + 1, indexAllotedCards = size;
					while (index-- > 0){
						cr = new Card(_discardCard.getIndex());
						cr.setIsOpened(true);
						_discardDeck[_indexDiscardDeck - 1] = null;
						_indexDiscardDeck--;
						
						prp.setRUMMYMovesStr("&GetDisc^" + _discardCard.toString());
						
						if (_indexDiscardDeck > 0)
							_discardCard = _discardDeck[_indexDiscardDeck - 1];
						else
							_discardCard = null;
	
						newCardAdded = cr.toString();
						
						_cat.debug("came here : , card : " + cr.toString());

						prp._allotedCards[indexAllotedCards++] = cr;
					}
					
					//newCardAdded is now special. if the player doesn't use this card and tries to discard and end his turn
					//we have to add a penalty of 50 to his account
					_players[pos].discardCardTaken = true;
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " chose discarded card.";
				}

				// no need to change player
				if (!checkGameOver()) {
					_nextMovesAllowed = move_RUMMY_DISCARD | move_RUMMY_MELDEXIST
							| move_RUMMY_MELDNEW;
					broadcastMessage(-1);
				}
			}

			if (moveId == move_RUMMY_DISCARD) {
				_nextMoveExpTime = System.currentTimeMillis();
				_lastMove = move_RUMMY_DISCARD;
				_lastMovePos = pos;
				_lastMoveString = "DiscardedCard:" + type;
				prp.setRUMMYStatus(status_ACTIVE);
				_players[pos].rummyLeftTableTime = -1;
				prp.setRUMMYMoveId(move_RUMMY_DISCARD);
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);
				
				if (!_players[pos].newCardTaken && !_players[pos].discardCardTaken){
					_cat.debug("first get new card, then discard");
					sendErrorMessage(pos, 0);
					return;
				}

				int typeIndex = -1;

				if (type.compareToIgnoreCase("JO") == 0) {
					typeIndex = 161;
				} else if (type.compareToIgnoreCase("JO1") == 0) {
					typeIndex = 162;
				} else if (type.compareToIgnoreCase("JO2") == 0) {
					typeIndex = 163;
				} else if (type.compareToIgnoreCase("JO3") == 0) {
					typeIndex = 164;
				} else {
					Card ctemp = new Card(type);
					typeIndex = ctemp.getIndex();
					if (typeIndex == -1) {
						sendErrorMessage(pos, 2);
						return;
					}
				}
				
				//new rule : if discarded card was 1st of the many cards player took and he has not melded it
				//he has to pay penalty of 50
				if (prp.discardCardTaken){
					for (int i = 0; i < prp._allotedCards.length; i++) {
						if (prp._allotedCards[i].toString().compareToIgnoreCase(newCardAdded) == 0) {
							prp.totalScore -= 50;
							sendErrorMessage(pos, 6);
						} 
					}
				}
				
				//check if card is there in alloted cards. if not, send error back
				boolean cardAvail = false;
				for (int i = 0; i < prp._allotedCards.length; i++){
					if (prp._allotedCards[i].getIndex() == typeIndex){
						cardAvail = true;
						break;
					}
				}
				
				if (!cardAvail){
					//card is not present. send error back
					sendErrorMessage(pos, 1);
					return;
				}
				
				// get rid of card - index is in type
				int[] newCards = new int[prp._allotedCards.length - 1];
				int j = 0;
				boolean found = false;
				for (int i = 0; i < prp._allotedCards.length; i++) {
					if (prp._allotedCards[i].getIndex() != typeIndex || found) {
						newCards[j++] = prp._allotedCards[i].getIndex();
					} else {
						found = true;// found 1st instance of this card being
										// removed. if there are 2 cards and
										// player wants to get rid of 1, then
										// other should stay.
					}
				}
				prp._allotedCards = new Card[newCards.length];
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
				
				prp.setRUMMYMovesStr("&Discard^" + _discardCard.toString());

				if (!checkGameOver()) {
					//with discard done, move for player is over. clear the flags now
					prp.newCardTaken = false;
					prp.discardCardTaken = false;
					
					_nextMovePlayer = getNextActivePos(_nextMovePlayer);
					_nextMovesAllowed = move_RUMMY_NEWCARD |  move_RUMMY_MELDEXIST
							| move_RUMMY_MELDNEW;;
					broadcastMessage(-1);
				}
			}
			
			//	cards come as AS`KS`QS
			if (moveId == move_RUMMY_MELDNEW) {
				_nextMoveExpTime = System.currentTimeMillis();
				_lastMove = move_RUMMY_MELDNEW;
				_lastMovePos = pos;
				_lastMoveString = "NewMeldedCards:" + type;
				prp.setRUMMYStatus(status_ACTIVE);
				_players[pos].rummyLeftTableTime = -1;
				prp.setRUMMYMoveId(move_RUMMY_MELDNEW);
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);
				
				//get all cards in new meld
				String[] toks = type.split("\\`");
				Card[] crds = new Card[toks.length];
				int index = 0, jokerIndex = 0;
				String[] jokerDesc = new String[toks.length];
				String strMeldCheck = "", actualMeldToBeAdded = "";
				
				for (int i = 0; i < toks.length; i++){
//					_cat.debug("vikas vikas hack hack " + toks[i]);
					//for each card, check if this card exists in players cards array.
					//if not, drop this message and send error back
					if (!toks[i].contains("JO")) {
						Card ctemp = new Card(toks[i]);
						boolean flag = checkIfCardPresent(prp, ctemp);
						if (!flag){
							//card is not present. send error back
							sendErrorMessage(pos, 1);
							return;
						}
						
						crds[index++] = ctemp;
						strMeldCheck += toks[i] + "`";
						actualMeldToBeAdded += toks[i] + "`";
					}
					else {
						//joker comes like JO-AS
						String[] toks2 = toks[i].split("\\-");
						if (toks2.length < 2 || toks2[1].isEmpty() || toks2[1].compareTo(" ") == 0){
							sendErrorMessage(pos, 3);
							return;
						}
						
						Card ctemp = new Card(toks2[0]);
						boolean flag = checkIfCardPresent(prp, ctemp);
						if (!flag){
							//card is not present. send error back
							sendErrorMessage(pos, 1);
							return;
						}
						
						//check if a valid card name is following Joker. It should be JO-KH, never JO-
//						_cat.debug("card after joker is : " + toks2[1]);
						
						crds[index++] = ctemp;
						jokerDesc[jokerIndex++] = toks2[1];
						strMeldCheck += toks2[1] + "`";
						actualMeldToBeAdded += toks2[0] + "`";
					}
				}
				
				if (!strMeldCheck.isEmpty()){
					strMeldCheck = strMeldCheck.substring(0, strMeldCheck.length() - 1);
					actualMeldToBeAdded = actualMeldToBeAdded.substring(0, actualMeldToBeAdded.length() - 1);
				}
				//verify that meld is proper. else drop the message and send error
				_cat.debug("string of meld to be checked : " + strMeldCheck);
				String res = countRunsAsArranged(strMeldCheck);
				if (res.isEmpty()){
					//erro message
					_cat.debug("wrong new meld. returning...");
					sendErrorMessage(pos, 3);
					return;
				}
				
				//meld is proper. now verify that player has not sent it more than once
				
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " melded a new one.";
				
				_cat.debug("_index of melds : " + _indexAllMeldedCards);
				int countMeldPresent = 0;
				if (_indexAllMeldedCards > 0) {
					for (int m = 0; m < _indexAllMeldedCards; m++){
						if (strMeldCheck.compareToIgnoreCase(_allMeldedCards[m]) == 0){
							countMeldPresent++;
							
							if (countMeldPresent > NUM_DECKS) {
								_cat.debug("meld already here. how can you send it again?");
								sendErrorMessage(pos, 3);
								return;
							}
						}
					}
				}
				
				_cat.debug("reached here 11111, valid meld");
				//check if ace,two and three. if so, ace is to be marked
				//it is a valid meld so if we check for presence of ace and two, we should be good to go
				//check if we have an ace
				boolean acePresent = false;
				int aceIndex = -1;
				for (int i = 0; i < crds.length; i++){
					if (crds[i].getHighBJRank() >= 11){
						acePresent = true;
						aceIndex = i;
						break;
					}
				}
				boolean twoPresent = false;
				if (acePresent){
					for (int i = 0; i < crds.length; i++){
						if (crds[i].getHighBJRank() == 2){
							twoPresent = true;
							break;
						}
					}
				}
				
				_cat.debug("acepresent : " + acePresent + " , twopresent : " + twoPresent + " aceindex : " + aceIndex);
				
				//add to allMeldedcard
				_allMeldedCards[_indexAllMeldedCards++] = actualMeldToBeAdded;//strMeldCheck;
				_cat.debug("added meld string : " + strMeldCheck + " new index : " + _indexAllMeldedCards + " actual meld is " + actualMeldToBeAdded);
				//store the joker descriptors. need to send them to all clients.haha.
				if (jokerIndex > 0){
					String jokDes = jokerDesc[0];
					if (jokerIndex > 1){
						//we have 2 or more jokers. whatta joke?
						for (int i = 1; i < jokerIndex; i++){
							jokDes += "`" + jokerDesc[i];
						}
					}
					
					_allJokersDesc[_indexAllMeldedCards-1] = jokDes;
				}

				// get rid of cards - index is in type
				if (prp._allotedCards.length - crds.length > 0){
					int[] newCards = new int[prp._allotedCards.length - crds.length];
					int j = 0;
					
					//arraylist to keep cards to be added
					ArrayList<Integer> al = new ArrayList<Integer>(5);
					for (int i = 0; i < prp._allotedCards.length; i++) {
						//for each card, verify that this card is not there in crds. remember there can be 2 instances of same card
						boolean found = false;
						for (int k = 0; k < crds.length; k++){
							if (prp._allotedCards[i].getIndex() == crds[k].getIndex() && !al.contains(crds[k].getIndex())) {
								found = true;
								//change the crds card at kth index now. we have already eliminated one. don't want to eliminate similar card again
	//							crds[k] = new Card(-1);
							}
						}
						
						if (!found){
							//add the card now
							newCards[j++] = prp._allotedCards[i].getIndex();
							_cat.debug("new card is : " + newCards[j-1]);
						}
						else {
							//only for duplicate cards
							al.add(prp._allotedCards[i].getIndex());
						}
					}
					
					prp._allotedCards = new Card[newCards.length];
					for (int i = 0; i < newCards.length; i++) {
						Card crd = new Card(newCards[i]);
						crd.setIsOpened(true);
						prp._allotedCards[i] = crd;
						// _cat.debug("card added : " + crd.toString());
					}
				}
				else {
					prp._allotedCards = null;
				}
				
				if (prp._allotedCards != null)
					_cat.debug("cards changed. alloted cards lenght : " + prp._allotedCards.length);
				
				//now fix the aces as twos thing
				if (acePresent && twoPresent){
					//we do have ace and two. hooha. change ace value to 111.
					Card ctemp = new Card(111);
					crds[aceIndex] = ctemp;
					_cat.debug("card changed for ace at location : " + aceIndex);
				}
				for (int i = 0; i < crds.length; i++)
					prp._meldedCards[prp._indexMeldedCards++] = crds[i];
				
				_cat.debug("melded cards added to player");

				prp.setRUMMYMovesStr("&MeldNew^" + type);
				
				//check here if all cards of players have been melded
				if (!checkGameOver()) {
					if (!checkNewCardTaken(_nextMovePlayer)) {
						_nextMovesAllowed = move_RUMMY_NEWCARD |  move_RUMMY_MELDEXIST
								| move_RUMMY_MELDNEW;;
						broadcastMessage(-1);
					}
					else {
						_nextMovesAllowed = move_RUMMY_DISCARD |  move_RUMMY_MELDEXIST
								| move_RUMMY_MELDNEW;;
						broadcastMessage(-1);
					}
				}
			}
			
			//			cards come as 2'AS to tell us that 2nd meld (3rd from left) is going to have a new card AS
			if (moveId == move_RUMMY_MELDEXIST) {
				_nextMoveExpTime = System.currentTimeMillis();
				_lastMove = move_RUMMY_MELDEXIST;
				_lastMovePos = pos;
				_lastMoveString = "ExistingMeldCards:" + type;
				prp.setRUMMYStatus(status_ACTIVE);
				_players[pos].rummyLeftTableTime = -1;
				prp.setRUMMYMoveId(move_RUMMY_MELDEXIST);
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);
				
				String[] oldToks = type.split("\\'");
				String[] toks = oldToks[1].split("\\`");
				
				//get all cards from the existing meld
				int meldIndex = Integer.parseInt(oldToks[0]);
				if (meldIndex < 0){
					sendErrorMessage(pos, 1);
					return;
				}
				
				
				String meld = _allMeldedCards[meldIndex];
				
				String[] meldCards = meld.split("\\`");
				Card[] crds = new Card[toks.length];
				//now get cards from the type
				int index = 0, jokerIndex = 0;
				String strMeldCheck = "", actualMeldToBeAdded = "";
				
				for (int i = 0; i < meldCards.length; i++){
					strMeldCheck += meldCards[i] + "`";
					actualMeldToBeAdded += meldCards[i] + "`";
				}
				
				//run a loop through str meld to check to ensure that a joker card used as a card is not being forced to change its value
				if (!_allJokersDesc[meldIndex].isEmpty()){
					//there are some jokers being used in this meld. get their settled values from alljokersdesc
					//take care that there may be many jokers in this meld. 
					String[] joks = _allJokersDesc[meldIndex].split("\\`");
					int joksIndex = 0;
					
					String tempString = "";
					
					//remove teh trailing ` from strmeldcheck
					strMeldCheck = strMeldCheck.substring(0, strMeldCheck.length() - 1);
					
					String[] meldTokens = strMeldCheck.split("\\`");
					for (int k = 0; k < meldTokens.length; k++){
						if (meldTokens[k].contains("JO")){
							tempString += joks[joksIndex++];
						}
						else {
							tempString += meldTokens[k];
						}
						
						tempString +=  "`";
					}
					
					if (tempString.length() > 0)
						tempString = tempString.substring(0, tempString.length() - 1);
					
					_cat.debug("temp strig is : " + tempString);
					strMeldCheck = tempString + "`";
				}
				
				
				String[] jokerDesc = new String[toks.length];
				for (int i = 0; i < toks.length; i++){
					if (!toks[i].contains("JO")) {
						Card ctemp = new Card(toks[i]);
						boolean flag = checkIfCardPresent(prp, ctemp);
						if (!flag){
							//card is not present. send error back
							sendErrorMessage(pos, 1);
							return;
						}
						
						crds[index++] = ctemp;
						strMeldCheck += toks[i] + "`";
						actualMeldToBeAdded += toks[i] + "`";
					}
					else {
						//joker comes like JO-AS
						String[] toks2 = toks[i].split("\\-");
						if (toks2.length < 2 || toks2[1].isEmpty() || toks2[1].compareTo(" ") == 0){
							sendErrorMessage(pos, 3);
							return;
						}
						
						Card ctemp = new Card(toks2[0]);
						boolean flag = checkIfCardPresent(prp, ctemp);
						if (!flag){
							//card is not present. send error back
							sendErrorMessage(pos, 1);
							return;
						}
						
						//check if a valid card name is following Joker. It should be JO-KH, never JO-
						_cat.debug("card after joker is : " + toks2[1]);
						
						crds[index++] = ctemp;
						jokerDesc[jokerIndex++] = toks2[1];
						strMeldCheck += toks2[1] + "`";
						actualMeldToBeAdded += toks2[0] + "`";
						
						_cat.debug("joker to be replaced with " + jokerDesc[jokerIndex-1]);
					}
				}
				
				if (!strMeldCheck.isEmpty()) {
					strMeldCheck = strMeldCheck.substring(0, strMeldCheck.length() - 1);
					actualMeldToBeAdded = actualMeldToBeAdded.substring(0, actualMeldToBeAdded.length() - 1);
				}
				//verify that meld is proper. else drop the message and send error
				_cat.debug("new meld to be tested : " + strMeldCheck);
				String res = countRunsAsArranged(strMeldCheck);
				if (res.isEmpty()){
					//erro message
					_cat.debug("sending error that meld is wrong...");
					sendErrorMessage(pos, 3);
					return;
				}
				
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " melded a card.";
				
				//check if ace,two and three. if so, ace is to be marked
				//it is a valid meld so if we check for presence of ace and two, we should be good to go
				//check if we have an ace
				boolean acePresent = false;
				int aceIndex = -1;
				String[] tempToks = strMeldCheck.split("`");
				for (int i = 0; i < tempToks.length; i++){
					Card ctemp = new Card(tempToks[i]);
					
					if (ctemp.getHighBJRank() >= 11){
						acePresent = true;
						break;
					}
				}
				boolean twoPresent = false;
				if (acePresent){
					for (int i = 0; i < tempToks.length; i++){
						Card ctemp = new Card(tempToks[i]);
						if (ctemp.getHighBJRank() == 2){
							twoPresent = true;
							break;
						}
					}
				}
				
				//now we know that we have both Ace and Two in this meld. check if the crds have these cards.
				//if so, then we have to do something for ace. else doesn't matter. the cards were already there
				if (acePresent && twoPresent){
					for (int i = 0; i < crds.length; i++){
						if (crds[i].getHighBJRank() >= 11){
							_cat.debug("Ace is sent to melded with existing meld");
							aceIndex = i;
							break;
						}
					}
				}
				
				if (aceIndex == -1){
					acePresent = false;
				}
				
				_cat.debug("acepresent : " + acePresent + " 2 presnet : " + twoPresent + " aceindex : " + aceIndex);
				
				//update allmeldecards with new meld. it is valid
				_allMeldedCards[meldIndex] = actualMeldToBeAdded;//strMeldCheck;
				_cat.debug("added meld string : " + strMeldCheck + " at index : " + meldIndex + " real actual meld is : " + actualMeldToBeAdded);
				_cat.debug("jokerindex is " + jokerIndex);
				//store the joker descriptors. need to send them to all clients.haha.
				if (jokerIndex > 0){
					String jokDes = jokerDesc[0];
					_cat.debug("orig value of jokdes is " + jokDes);
					if (jokerIndex > 1){
						//we have 2 or more jokers. whatta joke?
						for (int i = 1; i < jokerIndex; i++){
							jokDes += "`" + jokerDesc[i];
						}
					}
					
					_cat.debug("orig value of alljokersdesc at this index is " + _allJokersDesc[meldIndex]);
					if (!_allJokersDesc[meldIndex].isEmpty())
						_allJokersDesc[meldIndex] += "`" + jokDes;
					else
						_allJokersDesc[meldIndex] = jokDes;
					
					_cat.debug("added jokdes which is " + jokDes);
				}
				
				// get rid of cards - index is in type
				int newSize = prp._allotedCards.length - crds.length;
				if (newSize > 0) {
					int[] newCards = new int[newSize];
					int j = 0;
					ArrayList<Integer> al = new ArrayList<Integer>(5);
					
					for (int i = 0; i < prp._allotedCards.length; i++) {
						//for each card, verify that this card is not there in crds. remember there can be 2 instances of same card
						boolean found = false;
						for (int k = 0; k < crds.length; k++){
							if (prp._allotedCards[i].getIndex() == crds[k].getIndex() && !al.contains(crds[k].getIndex())) {
								found = true;
								//change the crds card at kth index now. we have already eliminated one. don't want to eliminate similar card again
	//							crds[k] = new Card(-1);
							}
						}
						
						if (!found){
							//add the card now
							newCards[j++] = prp._allotedCards[i].getIndex();
						}
						else {
							//only for duplicate cards
							al.add(prp._allotedCards[i].getIndex());
						}
					}
					
					prp._allotedCards = new Card[newCards.length];
					for (int i = 0; i < newCards.length; i++) {
						Card crd = new Card(newCards[i]);
						crd.setIsOpened(true);
						prp._allotedCards[i] = crd;
						_cat.debug("card added : " + crd.toString());
					}
				}
				else {
					prp._allotedCards = null;
				}
				
				if (acePresent && twoPresent){
					//we do have ace and two. hooha. change ace value to 111.
					Card ctemp = new Card(111);
					crds[aceIndex] = ctemp;
					_cat.debug("changed ace card here");
				}
				
				for (int i = 0; i < crds.length; i++)
					prp._meldedCards[prp._indexMeldedCards++] = crds[i];
				
				_cat.debug("Added existing card to existing meld.....");

				//type here is 0'KC. can't have '. creates problem.
				String tempType = type.replace("'", "`");
				prp.setRUMMYMovesStr("&MeldExist^" + tempType);

				if (!checkGameOver()) {
					if (!checkNewCardTaken(_nextMovePlayer)) {
						_nextMovesAllowed = move_RUMMY_NEWCARD |  move_RUMMY_MELDEXIST
								| move_RUMMY_MELDNEW;;
						broadcastMessage(-1);
					}
					else {
						_nextMovesAllowed = move_RUMMY_DISCARD |  move_RUMMY_MELDEXIST
								| move_RUMMY_MELDNEW;;
						broadcastMessage(-1);
					}
				}
			}
			
		}
		
		private boolean checkIfCardPresent(RummyProfile prp, Card ctemp){
			boolean cardAvail = false;
			for (int j = 0; j < prp._allotedCards.length; j++){
				if (prp._allotedCards[j].getIndex() == ctemp.getIndex()){
					cardAvail = true;
					break;
				}
			}
			
			return cardAvail;
		}
		
		public boolean checkNewCardTaken(int pos){
			return _players[pos].newCardTaken || _players[pos].discardCardTaken;
		}

		public void fixDealerFirst() {
			if (_gameOngoing)
				return;

			_cat.debug("fixDealerFirst");
			roundStarted = true;
			
			// now clear out the all in players list
			resetTable();
			
			_cardDealingOrder = "";
			
			//do it so that all variables are cleared
			initGame();
//			startNewRound();

			if (true) {

				_deck.clear();
				fixingDealerOngoing = true;
				
				counterGameNotStarted = 0;

				for (int m = 0; m < MAX_PLAYERS; m++) {
					if (_players[m] != null
							&& _players[m].getPresence() != null 
							&& isActive(_players[m])) {
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
				
				_cat.debug("least card bearer : " + leastCardBearer);

				_dealer = leastCardBearer;
				_rummyPlayer = getRightOfPlayer(_dealer);
				broadcastMessage(-1);

				// now start the game - have to wait for some seconds to give
				// time to client to display the cards and the new dealer and
				// rummy player
				// based on sitting players, let us see if we can start a game
				try {
					Thread.currentThread().sleep(2000);
				} catch (InterruptedException ee) {
					// continue
				}
				fixingDealerOngoing = false;
//				fixingDealerNextHand = false;
				countHandsAfterDealerFix = 0;
			} 
//			else {
//				countHandsAfterDealerFix++;
//				if (countHandsAfterDealerFix >= 4)
//					fixingDealerNextHand = true;
//			}

			startGameIfPossible();
		}

		public void startGameIfPossible() {
			if (_gameOngoing)
				return;

			_cat.debug("from startgameifpossible");
			
			removePlayersDisconnected();

			// game can be500 ---
			// now clear out the all in players list
			// resetTable();

			int _countPlayersInit = getCountActivePlayers();
			_cat.debug("startgame - " + _countPlayersInit);
			if (_countPlayersInit >= 2) {
				if (_countPlayersInit <= 4){
					NUM_DECKS = 1;
				}
				else {
					NUM_DECKS = 2;
				}

				MAX_CARDS = 52 * NUM_DECKS - 1;
				
				if (_countPlayersInit == 2)
					maxCardsToPlay = 13;
				else
					maxCardsToPlay = 7;
				
				_cat.debug("startgame : num decks : " + NUM_DECKS + " , max cards : " + MAX_CARDS + 
						" cards to play with : " + maxCardsToPlay);

				initGame();
				
				int randCard = drawCard();
				_discardCard = new Card(randCard);
				_discardCard.setIsOpened(true);

				_discardDeck[_indexDiscardDeck++] = _discardCard;
				
				countNames = 0;
				names = new String[MAX_PLAYERS];

				// now initialize the variables
				for (int m = 0; m < MAX_PLAYERS; m++) {
					if (_players[m] != null
					// && _players[m].getPresence() != null
							&& !isSittingOut(_players[m])
					) {
						_players[m]._firstMoveDone = false;
						_players[m]._allotedCards = new Card[maxCardsToPlay];
						_players[m].setRUMMYWinAmt(0);
						_players[m].setRUMMYStatus(status_ACTIVE);
						names[countNames++] = _players[m].name;
					}
				}

				// now set teh _cardDealingOrder starting from right of dealer -
				// haha - right means decrement by 1
				// but first check if dealer is still pointing to a valid player
				// - if not, shift it now
				if (_dealer == -1)
					_dealer = 0;
				if (_players[_dealer] == null || isSittingOut(_players[_dealer])
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

				_gameStartTime = Calendar.getInstance();

				_gameOngoing = true;
				
				_amtWonString = "";

				startNewRound();

				// // sleep for 10 seconds to allow clients to distribute cards
				 try {
				 Thread.currentThread().sleep(15000);
				 } catch (InterruptedException e) {
				 // TODO Auto-generated catch block
				 e.printStackTrace();
				 }
				//
				 
				 _nextMovePlayer = _rummyPlayer;
				 _nextMoveExpTime = System.currentTimeMillis();
				 broadcastMessage(-1);
			} else {
				_gameOngoing = false;
				_dealer = -1;
				_rummyPlayer = -1;
				removePlayersDisconnected();
				// for the unfortunate player who might be seated on teh table
				if (_countPlayersInit == 1)
					broadcastMessage(-1);
			}
		}

		public void startNewRound() {
			lastRespTime = System.currentTimeMillis();
			
			_cat.debug("from start new round");
			
//			_drawString = "";
			_currRoundResStr = "";
//			rummygrid = setNextGameRunId();
			_nextMovesAllowed = move_RUMMY_NEWCARD |  move_RUMMY_MELDEXIST
					| move_RUMMY_MELDNEW;

			_roundWinnerName = "";
			
			// give cards to all players
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null && _players[m].getPresence() != null &&
						(isActive(_players[m]))
						) {
					
					//dealer, rummy joker card, points per rupee, table id
					_players[m].setRUMMYMovesStr("&TID^" + _tid);
					_players[m].setRUMMYMovesStr("&Dealer^" + _dealer);
					_players[m].setRUMMYMovesStr("&PtsPerRupee^" + POINTSPERRUPEE);
					
					_players[m].newCardTaken = false;
					_players[m].discardCardTaken = false;
					
					_players[m]._meldedCards = new Card[100];
					_players[m]._indexMeldedCards = 0;
					
					_players[m].cardsStrFromClient = "";
					_players[m]._allotedCards = new Card[maxCardsToPlay];// always clear
																// the hand
																// here

					//player got cards. store it in db
					_players[m].setRUMMYMovesStr("&Cards^");
					for (int i = 0; i < maxCardsToPlay; i++) {
						int randCard = drawCard();
						Card cr = new Card(randCard);
						cr.setIsOpened(true);
						_players[m]._allotedCards[i] = cr;
						
						_players[m].setRUMMYMovesStr(cr.toString());
						if (i < maxCardsToPlay-1)
							_players[m].setRUMMYMovesStr("`");
					}
					
					_cat.debug("hack string for player " + _players[m].getRUMMYMovesStr());
				}
			}

			broadcastMessage(-1);
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

		public void broadcastMessage(int noUsePos) {
			_cat.debug("broadcasting response!!!");
			StringBuffer temp = new StringBuffer();
			temp.append("Rummy500Server=Rummy500Server");
			temp.append(",gid=").append(_gid);
			temp.append(",grid=").append(rummygrid);
			temp.append(",rummygrid=").append(rummygrid);
			temp.append(",TID=").append(_tid);

			temp.append(",Dealer=").append(_dealer);
			temp.append(",RummyPlayer=").append(_rummyPlayer);
			temp.append(",GameOn=").append(_gameOngoing);
			
			if (_nextMovePlayer != -1 && _nextMovePlayer < MAX_PLAYERS && _players[_nextMovePlayer] != null && _winnerPos == -1){
				//for time bank
				if (_players[_nextMovePlayer].getUsingTimeBank()){
					temp.append(",DiscProtOn=").append((_players[_nextMovePlayer].getTimeBank()) * 10);
				}
			}

//			if (_discardCard != null)
//				temp.append(",DiscardCard=").append(_discardCard.toString());
//			else
//				temp.append(",DiscardCard=");

//			if (_firstCard != null)
//				temp.append(",FirstCard=").append(_firstCard.toString());
//			else
//				temp.append(",FirstCard=");
			
			temp.append(",chatMsgOn=").append(chatOn);
			if (!chatMessage.isEmpty()){
				String strcrypt = encrypt(chatMessage);
				temp.append(",chatMsg=").append(strcrypt);
//				_cat.debug(decrypt(strcrypt));
				chatMessage = "";
			}
			else
				temp.append(",chatMsg=");

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
			
			//send all discarded cards to players
			String disCrds = "";
			for (int k = 0; k < _indexDiscardDeck; k++){
				disCrds += _discardDeck[k] + "|";
			}
			if (!disCrds.isEmpty())
				disCrds = disCrds.substring(0, disCrds.length() - 1);
			temp.append(",Discards=").append(disCrds);

			//send all the melded cards to all players
			String meldCards = "";
			for (int k = 0; k < _indexAllMeldedCards; k++){
				meldCards += _allMeldedCards[k] + "|";
			}
			if (!meldCards.isEmpty())
				meldCards = meldCards.substring(0, meldCards.length() - 1);
			temp.append(",Melds=").append(meldCards);
			
			String jokDescCards = "";
			for (int k = 0; k < _indexAllMeldedCards; k++){
				if (_allJokersDesc[k] != null) {
					if (!_allJokersDesc[k].isEmpty())
						jokDescCards += _allJokersDesc[k] + "|";
					else
						jokDescCards += "|";
				}
			}
			if (!jokDescCards.isEmpty())
				jokDescCards = jokDescCards.substring(0, jokDescCards.length() - 1);
			temp.append(",JUsedAs=").append(jokDescCards);

			temp.append(",RoundResult=").append(_drawString);
			
			if (_winnerPos != -1) {
				temp.append(",Winner=").append(_winnerPos);
				temp.append(",RoundWinner=").append(_roundWinnerName);
				temp.append(",CurrentRoundResult=").append(_currRoundResStr);
				if (_players[_winnerPos] != null)
					temp.append(",WinPoints=").append(_players[_winnerPos].getRUMMYWinAmt());
				
				temp.append(",AmountsWon=").append(_amtWonString);
			} else {
				if (!_drawString.isEmpty()) {
					temp.append(",CurrentRoundResult=").append(_currRoundResStr);
					temp.append(",RoundWinner=").append(_roundWinnerName);
				}
			}
			// now create that amalgam of all players status, pos, chips
			StringBuffer tempPlayerDet = new StringBuffer();
			int nCount = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null) {
					nCount++;
					tempPlayerDet.append("'" + _players[i]._pos + ":" + _players[i].getName()
							+ ":");
					tempPlayerDet.append(_players[i].getRUMMYStatus() + ":"
							+ _players[i].getPresence().getAmtAtTable());
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
							&& !fixingDealerOngoing && _gameOngoing) {
						String str = "";
						for (int k = 0; k < _players[i]._allotedCards.length; k++)
							str += _players[i]._allotedCards[k].toString()
									+ "'";
						
						if (!str.isEmpty())
							str = str.substring(0, str.length() - 1);
						
						tempPlayerData.append(",Cards=" + str);
					} else
						tempPlayerData.append(",Cards=");

					tempPlayerData.append(",NewCardAdded=" + newCardAdded);
					sendMessage(tempPlayerData, _players[i]);
					_cat.debug("msg sent : " + tempPlayerData);
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
			temp.append("Rummy500Server=Rummy500Server");
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
						"Didnt play in last 5 hands!");
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
			
			//send all discarded cards to players
			String disCrds = "";
			for (int k = 0; k < _indexDiscardDeck; k++){
				disCrds += _discardDeck[k] + "|";
			}
			if (!disCrds.isEmpty())
				disCrds = disCrds.substring(0, disCrds.length() - 1);
			temp.append(",Discards=").append(disCrds);
			
			//send all the melded cards to all players
			String meldCards = "";
			for (int k = 0; k < _indexAllMeldedCards; k++){
				meldCards += _allMeldedCards[k] + "|";
			}
			if (!meldCards.isEmpty())
				meldCards = meldCards.substring(0, meldCards.length() - 1);
			temp.append(",Melds=").append(meldCards);
			
			String jokDescCards = "";
			for (int k = 0; k < _indexAllMeldedCards; k++){
				jokDescCards += _allJokersDesc[k] + "|";
			}
			if (!jokDescCards.isEmpty())
				jokDescCards = jokDescCards.substring(0, jokDescCards.length() - 1);
			temp.append(",JUsedAs=").append(jokDescCards);


			_cat.debug("temp from kick out : " + temp);

			// this temp can be now sent to all observers on the table
			for (int i = 0; i < _observers.size(); i++) {
				RummyProfile pro = (RummyProfile) _observers.get(i);
				if (!pro.isRummyPlayer()) {
					sendMessage(temp, pro);
				}
			}
			// for each presence, call sendMessage with their individual data
			int i = prpos;
			// for (int i = 0; i < MAX_PLAYERS; i++) {
			if (i > -1 && _players[i] != null) {
				StringBuffer tempPlayerData = new StringBuffer(temp);
				tempPlayerData.append(",PlayerPos=").append(i);
				sendMessage(tempPlayerData, _players[i]);
			}
			// }
		}

		public void sendErrorMessage(int prpos, int resCode) {
			StringBuffer temp = new StringBuffer();
			temp.append("Rummy500Server=Rummy500Server");
			temp.append(",gid=").append(_gid);
			temp.append(",grid=").append(rummygrid);
			temp.append(",rummygrid=").append(rummygrid);
			temp.append(",TID=").append(_tid);

			// for reason
			if (resCode == 0) {
				temp.append(",MsgDropped=").append("WrongMove");
			} else if (resCode == 1) {
				temp.append(",MsgDropped=").append("WrongCard");
			} else if (resCode == 3) {
				temp.append(",MsgDropped=").append("WrongMeld");//wrong move click
			} else if (resCode == 4) {
				temp.append(",MsgDropped=").append("WrongAction");//can't do it
			} else if (resCode == 5) {
				temp.append(",MsgDropped=").append("OnlyDeclareAllowed");//can't do it
			} else if (resCode == 6) {
				temp.append(",MsgDropped=").append("PenaltyCardNotMelded");//can't do it
			} 
			else if (resCode == 11) {
				temp.append(",PenalCards=" + globalPenal + "'firstLife="
						+ globalFirstLife + "'secondLife=" + globalSecondLife);
			} else {
				temp.append(",MsgDropped=GetLost");
			}

			temp.append(",Dealer=").append(_dealer);
			temp.append(",RummyPlayer=").append(_rummyPlayer);
			temp.append(",GameOn=").append(_gameOngoing);
			
			_cat.debug("from senderror message");

			if (_discardCard != null)
				temp.append(",DiscardCard=").append(_discardCard.toString());
			else
				temp.append(",DiscardCard=");

			temp.append(",NextMovePos=").append(_nextMovePlayer);
			temp.append(",NextMoveId=").append(_nextMovesAllowed);
			temp.append(",LastMove=-1");
			temp.append(",LastMovePos=-1");
			temp.append(",LastMoveType=");

			temp.append(",DealingOrder=").append(_cardDealingOrder);
			
			//send all discarded cards to players
			String disCrds = "";
			for (int k = 0; k < _indexDiscardDeck; k++){
				disCrds += _discardDeck[k] + "|";
			}
			if (!disCrds.isEmpty())
				disCrds = disCrds.substring(0, disCrds.length() - 1);
			temp.append(",Discards=").append(disCrds);
			
			//send all the melded cards to all players
			String meldCards = "";
			for (int k = 0; k < _indexAllMeldedCards; k++){
				meldCards += _allMeldedCards[k] + "|";
			}
			if (!meldCards.isEmpty())
				meldCards = meldCards.substring(0, meldCards.length() - 1);
			temp.append(",Melds=").append(meldCards);
			
			String jokDescCards = "";
			for (int k = 0; k < _indexAllMeldedCards; k++){
				jokDescCards += _allJokersDesc[k] + "|";
			}
			if (!jokDescCards.isEmpty())
				jokDescCards = jokDescCards.substring(0, jokDescCards.length() - 1);
			temp.append(",JUsedAs=").append(jokDescCards);

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
			if (i > -1 && _players[i] != null) {
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
				_cat.debug("error message : " + tempPlayerData);
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
			buf.append("Rummy500Server=Rummy500Server,grid=")
					.append(-1)
					.append(",MsgDropped=InvalidTable,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}

		int pos = getPos(movedet);
		if (pos < 0 || pos > MAX_PLAYERS) {
			buf.append("Rummy500Server=Rummy500Server,grid=")
					.append(-1)
					.append(",MsgDropped=InvalidPos,player-details=" + p.name()
							+ "|" + p.netWorth());
			return buf;
		}

		_tables[tid]._players[pos].setReloadReq(true);
		buf.append("Rummy500Server=Rummy500Server,grid=").append(p.getGRID())
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
		
		//cardsDet has to be empty for sit in. add a global index to it. make it different
		cardsDet += globalIndex++;

		StringBuffer buf = new StringBuffer();// for response back to player
		_cat.debug("Rummy500Server game - sit in req --" + movedet + " for player " + p.name());

		if (movedet == null || movedet.equals("null")) {
			return null;
		}
		if (tid >= MAX_TABLES || tid < 0 || !_tables[tid].validTable) {
			buf.append("Rummy500Server=Rummy500Server,grid=")
					.append(-1)
					.append(",TID=")
					.append(tid)
					.append(",MsgDropped=InvalidTable,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}

		int origPos = -1;
		origPos = _tables[tid].findPos(p);
		if (moveId == 0) {
			_cat.debug("came to observer...");
			// just wants to observe, it is ok
			if (!_tables[tid]._gameOngoing
					&& _tables[tid].getCurrentPlayersCount() == 0)
				_tables[tid]._nextMoveExpTime = System.currentTimeMillis();
			
			if (origPos != -1) {
				// found him, seated already.
				_cat.debug("seated already from rummysitin : "
						+ origPos);
			} else {

				if (p.getAmtAtTable() < 500 * _tables[tid].POINTSPERRUPEE) {
					_cat.debug("no chipsssssssssssss");
					buf.append("Rummy500Server=Rummy500Server,grid=")
							.append(-1)
							.append(",TID=")
							.append(tid)
							.append(",MsgDropped=PlayerBroke,player-details="
									+ p.name() + "|" + p.netWorth());
					return buf;
				}

				// create a new rummyprofile for this presence
				_cat.debug("successful obsedrver...");
				RummyProfile kp = new RummyProfile();
				kp.setName(p.name());
				kp.setGameStartWorth(p.getAmtAtTable());
				kp.setPresence(p);
				p.setKPIndex(_tables[tid].addObserver(kp));
				_cat.debug("ADDING OBSERVOER : " + kp.name);
				kp.setRUMMYStatus(0);
				kp.rummyLastMoveTime = System.currentTimeMillis();
				buf.append("Rummy500Server=Rummy500Server,grid=")
						.append(-1)
						.append(",TID=")
						.append(tid);
						
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
				&& p.getAmtAtTable() < 500 * _tables[tid].POINTSPERRUPEE) {
			_cat.debug("player doesn't have chips");
			buf.append("Rummy500Server=Rummy500Server,grid=")
					.append(-1)
					.append(",TID=")
					.append(tid)
					.append(",MsgDropped=PlayerBroke,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}
		_cat.debug("has enough money");
		// now check if there is space on the table
		if (origPos == -1
				&& _tables[tid].getCurrentPlayersCount() >= _tables[tid]
						.getMaxPlayers()) {
			_cat.debug("talbe fulllllll");
			buf.append("Rummy500Server=Rummy500Server,grid=")
					.append(p.getGRID())
					.append(",TID=")
					.append(tid)
					.append(",MsgDropped=TableFull,player-details=" + p.name()
							+ "|" + p.netWorth());
			return buf;
		}

		_cat.debug("table not full");

		// need to update the pos in the move det string to be shard with Rummy
		// thread
		StringBuffer movedet1 = new StringBuffer();
		int obsPos = origPos;
		if (obsPos == -1) {
			// player not seated already - check in observors list
			_cat.debug("obspos -1");
			obsPos = _tables[tid].findObsPos(p);
			movedet1.append(tid).append(",").append(obsPos).append(",")
					.append(moveId).append("," + type).append("," + cardsDet);
		} else {
			movedet1.append(tid).append(",").append(obsPos).append(",")
					.append(move_RUMMY_JOINED).append("," + type).append("," + cardsDet);
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
		_cat.debug("Rummy500Server game - move req --" + movedet);

		if (movedet == null || movedet.equals("null")) {
			buf.append("Rummy500Server=Rummy500Server,grid=")
					.append(-1)
					.append(",MsgDropped=MsgDropped,player-details=" + p.name()
							+ "|" + p.netWorth());
			return buf;
		}
		if (tid >= MAX_TABLES) {
			buf.append("Rummy500Server=Rummy500Server,grid=")
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

	public StringBuffer rummyTablesList(Player.Presence p, String mdDet) {
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
			_tables[tid]._players[kp.pos()] = null;
		}
		else {
			// just mark the player as left. on completion of cycle, the player will
			// be removed
			kp.rummyLeftTableTime = System.currentTimeMillis();
			kp.setRUMMYMovesStr("&Leave");
			kp.setRUMMYMoveId(move_RUMMY_LEFT);
			kp.setRUMMYStatus(status_LEFT);
			_tables[tid].handleMoveNoAction(kp.pos());
			
			_cat.debug("take action plyr left : " + kp.pos()
					+ " from table : " + tid);
			kp.unsetRummyPlayer();
		}
	}
	
	private static final int POINTS_TO_END_GAME = 500;

}
