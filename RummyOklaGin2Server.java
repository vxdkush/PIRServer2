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

public class RummyOklaGin2Server extends CasinoGame {
	static Logger _cat = Logger.getLogger(RummyOklaGin2Server.class.getName());
	String _name;
	double _minBet;
	double _maxBet;
	double totalBet = 0, totalWin = 0, totalGames = 0;
	String moveDetails = "";

	public static final int MAX_PLAYERS = 2;
	public static final int move_RUMMY_INVALID = 0;
	public static final int move_RUMMY_PASS = 1;
	public static final int move_RUMMY_NEWCARD = 2;
	public static final int move_RUMMY_DISCARD = 4;
	public static final int move_RUMMY_DECLAREGIN = 8;
	public static final int move_RUMMY_KNOCK = 16;
	public static final int move_RUMMY_ADJUST_LOSER = 32;
	public static final int move_RUMMY_LEFT = 64;
	public static final int move_RUMMY_JOINED = 128;
	public static final int move_RUMMY_DRAW = 256;
	public static final int move_RUMMY_CHAT = 512;
	
	public static final int move_RUMMY_SITOUT = 1024;
	public static final int move_RUMMY_SITIN = 2048;
	
	public static final int PTS_TO_WIN = 150;
	
	public int globalPenal = 0;
	public boolean globalFirstLife = false, globalSecondLife = false;

	public static final int status_NONE = 1;
	public static final int status_ACTIVE = 2;
	public static final int status_LEFT = 16;
	public static final int status_JOINED = 32;
	public static final int status_SITTINGOUT = 64;

	public static final int MAX_TABLES = 10;
	
	RummyOklaGinTable[] _tables = null;

	boolean _keepServicing = false;

	private LinkedList[] _msgQ = new LinkedList[MAX_TABLES];

	public void add(int index, Presence cmdStr) {
		(_msgQ[index]).add(cmdStr);
	}

	public Presence fetch(int index) {
		return (Presence) (_msgQ[index].removeFirst());
	}

	public RummyOklaGin2Server(String name, double minB, double maxB, GameType type,
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
		_tables = new RummyOklaGinTable[MAX_TABLES];
		for (int i = 0; i < MAX_TABLES; i++) {
			_tables[i] = new RummyOklaGinTable();
			_tables[i].validTable = true;
			_tables[i].POINTSPERRUPEE = 1.0;
			Thread t = new Thread(_tables[i]);
			t.setName("RummyOkla2Gin-Table-" + i);
			t.setPriority(Thread.NORM_PRIORITY);
			_tables[i].setIndex(i);
			t.start();

			_cat.debug("starting gin thread : " + i);
		}
	}

	public StringBuffer gameDetail() {
		StringBuffer sb;
		sb = new StringBuffer("RummyOklaGin2Server=RummyOklaGin2Server")
				.append(",min-bet=").append(_minBet).append(",max-bet=")
				.append(_maxBet).append(",RummyOklaGinTables=");
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

		public String cardsStrFromClient = "";

		public int fixPosCard = -1;
	}

	class RummyOklaGinTable implements Runnable {
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
		int _winnerPos = -1;// outer or inner - where did the winning card fall?

		boolean fixingDealerOngoing = false;
//		boolean fixingDealerNextHand = true;
		int countHandsAfterDealerFix = 0;
		
		int KNOCKING_POINTS_2 = 10;
		
		boolean doubleUpSpades = false;
		
		int errorCode = -1;

		String _winnerString = "";
		
		String _amtWonString = "";
		
		double dbwinamt = 0;

		String _drawString = "";
		String _currRoundResStr;
		
		String _roundWinnerName;

		String newCardAdded = "";

		String _cardDealingOrder = "";

		// the card for which the players wage war
		Card _discardCard, _firstCard;

		int tanalaCount = 0;
		int idPlayerKnocking = -1;

		String _knockingCardStr = "";

		int idPlayerValidDeclared = -1;

		int idPlayerKnockedUpon = -1;

		int countPlayerResponseDeclareLoser = 0;

		int _trackNumTimesDiscardPileReused = 0;

		boolean validTable = false;

		BitSet _deck = new BitSet(); // this is for fresh pile
		Card[] _discardDeck = new Card[65]; // when ever a card is discarded, we
											// add it here. when one is removed,
											// prev card comes to fore.
		// when the fresh pile runs out, we unset the bits in _desk that are set
		// in _discardDeck and empty out _discardDeck
		int _indexDiscardDeck;

		int NUM_DECKS = 1;
		int MAX_CARDS = 52 * NUM_DECKS - 1;

		// round winners
		String[] roundWinners = new String[MAX_PLAYERS];
		
		int[] roundWonCount = new int[MAX_PLAYERS];

		int[] totalPtsWon = new int[MAX_PLAYERS];

		int roundNo = 0;

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
		
		int maxCardsToPlay = 10;
		int numPlayersOnTable = 2;
		//0 means nothing. 1 means knock. 2 means gin.
		int isGin = 0;
		
		String chatMessage = "";
		int chatOn = 0;
		
		//for sitting out players
		ArrayList<Integer> sitoutPlayers = new ArrayList<Integer>();
		ArrayList<Integer> sitinPlayers = new ArrayList<Integer>();

		public RummyOklaGinTable() {
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

		public void resetTable() {
			//find a friend feature - clear out here
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null) {
					_players[m].getPresence().player().setActiveGame(-1);
				}
			}
			
			if(!_gameOngoing){
				handleSitoutSitin();
			}
			
			// first cull the players - this will leave some open seats
			// _cat.debug("from reset table");
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null) {
					if (isRemoved(_players[m])
							|| isLeft(_players[m])
							|| (_players[m].getPresence().getAmtAtTable() <= (500 * POINTSPERRUPEE))
					// ||!_players[m].getRUMMYBetApplied() //this condition is
					// to throw out the players who just sit on table
					) {
						sendMessageKickedOut(m, 0);
						_players[m] = null;
						broadcastMessage(-1);
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
					_players[m].getPresence().player().setActiveGame(3);
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
			_winnerPos = -1;
			_drawString = "";
			_currRoundResStr = "";
			rummygrid = setNextGameRunId();
			_nextMovesAllowed = move_RUMMY_PASS;
			_knockingCardStr = "";
			_roundWinnerName = "";
			idPlayerValidDeclared = -1;
			idPlayerKnockedUpon = -1;
			
			idPlayerKnocking = -1;

			countPlayerResponseDeclareLoser = 0;

			_nextMoveExpTime = System.currentTimeMillis();

			_deck.clear();
			_discardDeck = new Card[65];
			_indexDiscardDeck = 0;

			newCardAdded = "";

			_lastMovePos = -1;
			_lastMove = 0;
			_lastMoveString = "";

			_trackNumTimesDiscardPileReused = 0;
			_firstCard = new Card(drawCard());
			_firstCard.setIsOpened(true);
			_discardCard = null;
			
			//KNOCKING_POINTS has to be set after first card is dealt
			KNOCKING_POINTS_2 = _firstCard.getHighBJRank();
			if (KNOCKING_POINTS_2 == 11){
				KNOCKING_POINTS_2 = 0; //only gin is allowed. no knocking ever
			}
			
			//if first card is that of spades, then double the penalty
			doubleUpSpades = false;
			if (_firstCard.getSuit() == Card.SPADES){
				doubleUpSpades = true;
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
				if (_players[i] != null)
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
				System.out
						.println("can't find the player in observors list or old players : "
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
			
			//error case - sometimes there are old values. they sholdn't be here, clear them all
			_cat.debug("calling initgame from handlegamejoin req......");
			initGame();
			
			int pos = -1;
			if (!rejoinReq) {
				int countPlayersInit = getCountActivePlayers();
				_cat.debug("found " + countPlayersInit
						+ " players on table ");
				if (countPlayersInit > MAX_PLAYERS) {

					System.out
							.println("despite attempts no avail pos at table :"
									+ _tid + " for player : " + p.getName());
					return;

				} else {
					//check. game might be ongoing. if so, the player can't join. he can only sit and observe
					if(fixingDealerOngoing || _gameOngoing){
						//player can not join now. he will have to wait till next hand
						p.setRUMMYStatus(status_SITTINGOUT);
					}
					else {
						countPlayersInit = getCountTotalPlayers();
	
						if (countPlayersInit == 0) {
							pos = 0;// very first player
							_players[0] = p;
							_nextMoveExpTime = System.currentTimeMillis();
							resetTable();
						} else if (countPlayersInit == 1) {
							if (_players[1] != null)
								pos = 0;
							else
								pos = 1;
							
							_players[pos] = p;
							_nextMoveExpTime = System.currentTimeMillis();
						}
						p.setRUMMYStatus(status_ACTIVE);// the first 2 players have
														// to be marked active
						
						chatOn = 0;
						chatMessage = p.name + " at pos : " + (pos + 1) + " joined.";
						
						_players[pos].getPresence().player().setActiveGame(3);
						_players[pos].getPresence().player().setActiveTable(_tid);
						
						_players[pos].setTimeBank(30000);
					}
				}
			} else {
				// a player can't join back immediately on the same table he
				// left. it could be a ploy to cheat the house
				// TBD - check if currently playing players list has this name.
				// if so, then kick him out
				// else allow him
				pos = p._pos;
				p.setRUMMYStatus(status_JOINED);
				chatOn = 0;
				chatMessage = p.name + " at pos : " + (pos + 1) + " joined.";
				
				_players[pos].getPresence().player().setActiveGame(3);
				_players[pos].getPresence().player().setActiveTable(_tid);
				_players[pos].setTimeBank(30000);
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
			System.out
					.println("RummyOklaGin2Server game - sit in req buf -- on table : "
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
				broadcastMessage(-1);
			} else if (countPlayers == 2 && !_gameOngoing) {
				_winnerPos = -1;
				fixDealerFirst();
				// fixing dealer process already on, then no need to start it
				// again
//				if (!fixingDealerOngoing) {
//					fixingDealerNextHand = true;
//					fixDealerFirst();
//				}
			}
			
			// else {
			// // not calling start game from here - that has to be done from
			// // run method
			// fixingDealerNextHand = true;
			// p.setRUMMYStatus(status_JOINED + status_SITTINGOUT);
//			 broadcastMessage(-1);
			// }
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
			return isLeft(p);
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
						&& !isJoined(_players[i]))
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

		public int getCountTotalPlayers() {
			int cnt = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null && !isLeft(_players[i])
						&& !isRemoved(_players[i]))
					cnt++;
			}

			return cnt;
		}
		
		public int getCountSitInReqPlayers() {
			return sitinPlayers.size();
		}
		
		public int getCountSitOutPlayers() {
			int cnt = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null && isSittingOut(_players[i]))
					cnt++;
			}

			return cnt;
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
			if (pos < 0)
				return -1;
			
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
			while ((_players[pos1].getRUMMYStatus() & status_JOINED) > 0
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
			
			_cat.debug("chat : " + chatMessage);

			if (_nextMovePlayer != 111) {
				errorCode = 5;
				
				if (pos == _nextMovePlayer) {
					// same action as that of player leaving table
					handleTimedOut();
					return;
				}

				if (!checkGameOver()) {
					// game not over denotes a terrible error somewhere
					_gameOngoing = false;
					removePlayersDisconnected();
					errorCode = -1;
				}
			}
		}

		private void handleTimedOut() {
			_lastMovePos = _nextMovePlayer;
			if (_players[_nextMovePlayer].isEligibleTimeBank()) {
				_lastMoveString = "Folded";
				_players[_nextMovePlayer].setRUMMYMovesStr("&Folded");
				_players[_nextMovePlayer].setRUMMYStatus(status_JOINED);
			}
			else {
				//mark the player as left for he has now exhausted time bank
				_lastMoveString = "Left";
				_players[_nextMovePlayer].setRUMMYMovesStr("&TimedOut");
				_players[_nextMovePlayer].setRUMMYStatus(status_LEFT);
				_players[_nextMovePlayer].setTimeBank(-1);
				_players[_nextMovePlayer].setUsingTimeBank(false);
			}
			
			chatOn = 0;
			chatMessage = _players[_nextMovePlayer].name + " at pos : " + (_nextMovePlayer + 1) + " timed out.";
			
			_nextMoveExpTime = System.currentTimeMillis();
			
			checkGameOver();
		}

		// to be called after each round is over. if posWinner is -1, it means
		// that no winner is there
		// if posWinnier is > 0, then the player won calling GIN Rummy
		// if posWinner is 111, it means that it is a case of knocker and
		// knocked upon. check on their points to find out the winner
		public String declareRoundOver(int posWinner) {
			_nextMoveExpTime = System.currentTimeMillis();

			String resStr = "";

			StringBuffer sb = new StringBuffer();
			if (posWinner == -1) {
				// call for a draw
				_nextMovesAllowed = move_RUMMY_DRAW;
				_nextMovePlayer = 222;//-1;

				for (int m = 0; m < MAX_PLAYERS; m++) {
					if (_players[m] != null
							&& (isActive(_players[m]) && !isRemoved(_players[m]))) {
						// only non folded players are supposed to make moves
						countPlayerResponseDeclareLoser++;
					}
				}

				if (countPlayerResponseDeclareLoser == 0) {
					// no other player to wait on - end this drama now.
					_cat.debug("WEIRD!!!! there are no players on table");
					_gameOngoing = false;
					removePlayersDisconnected();
				} else {
					_roundWinnerName = "";
					
					int[] otherIndex = new int[numPlayersOnTable];
					int index = 0;
					
					String otherPlayerData = "";
					
					for (int k = 0; k < otherIndex.length; k++){
						if (_players[k] != null && isActive(_players[k])){
							otherIndex[index++] = k;
							
							String str2 = (roundNo + 1)
									+ "'" + k + "'0'0'0'"
									+ totalPtsWon[k] + "'3";
							
							if (!roundWinners[k].isEmpty())
								roundWinners[k] += "|" + str2;
							else
								roundWinners[k] = str2;
							
							otherPlayerData += str2 + "|";
							
							_cat.debug("string for draw : " + roundWinners[k]);
						}
					}
					
					roundNo++;
					
					_currRoundResStr = otherPlayerData;
					_currRoundResStr = _currRoundResStr.substring(0, _currRoundResStr.length() - 1);
					
					resStr = "";
					for (int k = 0; k < _players.length; k++){
						if (_players[k] != null && isActive(_players[k])){
							resStr += roundWinners[k] + "`";
						}
					}
					resStr = resStr.substring(0, resStr.length() - 1);
					
					_cat.debug("draw curr round res " + _currRoundResStr);
				}

				return resStr;
			} 
//			else if (posWinner == 222) {
//				// case where a draw is to be enforced
//				int[] pts = new int[2];
//				int j = 0;
//				for (int m = 0; m < MAX_PLAYERS; m++) {
//					if (_players[m] != null && isActive(_players[m])) {
//						pts[j] = checkValidCards2(_players[m]._allotedCards,
//								_players[m].cardsStrFromClient);
//
//						j++;
//					}
//				}
//
//				int otherIndex = -1, leastIndex = -1;
//				if (pts[0] > pts[1]) {
//					otherIndex = 0;
//					leastIndex = 1;
//				}
//				else {
//					otherIndex = 1;
//					leastIndex = 0;
//				}
//				int valEarned = pts[otherIndex] - pts[leastIndex];
//				//the person with lower deadwood wins, so leastIndex is the highIndex for us
//				int highIndex = leastIndex;
//				
//				totalPtsWon[highIndex] += valEarned;
//				_players[highIndex].setRUMMYPoints(valEarned);
//
//				String str = (roundNo + 1) + "'" + highIndex + "'" + valEarned
//						+ "'0'" + totalPtsWon[highIndex] + "'3";
//				if (!roundWinners[highIndex].isEmpty())
//					roundWinners[highIndex] += "|" + str;
//				else
//					roundWinners[highIndex] = str;
//				
//				String str2 = (roundNo + 1) + "'" + otherIndex + "'0'0'"
//						+ totalPtsWon[otherIndex] + "'3";
//				if (!roundWinners[otherIndex].isEmpty())
//					roundWinners[otherIndex] += "|" + str2;
//				else
//					roundWinners[otherIndex] = str2;
//				
//				if (highIndex == 0)
//					_currRoundResStr = str + "|" + str2;
//				else
//					_currRoundResStr = str2 + "|" + str;
//
//				idPlayerValidDeclared = highIndex;
//				
//				_roundWinnerName = _players[idPlayerValidDeclared].name;
//
//				roundWonCount[highIndex]++;
//
//				if (highIndex == 0)
//					resStr = roundWinners[highIndex] + "`" + roundWinners[otherIndex];
//				else
//					resStr = roundWinners[otherIndex] + "`" + roundWinners[highIndex];
//
//			} 
			else if (posWinner == 111) {
				// case where the knocker or the knocked out will win
				// compare the rummy points of 2 players - it is the deadwood
				// points
				
				_cat.debug("idplayerknocking : " + idPlayerKnocking);

				if (idPlayerKnocking != -1) {
					int valEarned = 0, bonusCnt = 0;
					String[] strRes = new String[MAX_PLAYERS];
					boolean knockingPersonWon = true;
					
					//compare with each player one by one. if someone undercuts the knocking player, he gets the undercut bonus of 20
					//if knocking player wins against someone, then he gets knocking bonus of 10. 
					//so a valid scenario could be knocking'15`1'25`2'0
//					for (int k = 0; k < idPlayerKnockedUpon.length; k++){
						if (idPlayerKnockedUpon > -1){
							if (_players[idPlayerKnockedUpon].getRUMMYPoints() <= _players[idPlayerKnocking]
									.getRUMMYPoints()){
								//case of undercut
								//first incr the line bonus counter
								roundWonCount[idPlayerKnockedUpon]++;
								knockingPersonWon = false;
								idPlayerValidDeclared = idPlayerKnockedUpon;
								
								int value = _players[idPlayerKnocking].getRUMMYPoints() - _players[idPlayerKnockedUpon].getRUMMYPoints();
								
								//if spades is first card, then double up
								if (doubleUpSpades)
									value *= 2;
								
								strRes[idPlayerKnockedUpon] = (roundNo + 1)
										+ "'" + idPlayerKnockedUpon + "'" + 
										_players[idPlayerKnockedUpon].getRUMMYPoints()
										+  "'" + value
										+ "'25'"
										+ (totalPtsWon[idPlayerKnockedUpon] + 25 + value)
										+ "'2";
								if (!roundWinners[idPlayerKnockedUpon].isEmpty())
									roundWinners[idPlayerKnockedUpon] += "|" + strRes[idPlayerKnockedUpon];
								else
									roundWinners[idPlayerKnockedUpon] = strRes[idPlayerKnockedUpon];
								
								_currRoundResStr += strRes[idPlayerKnockedUpon] + "|";
								
								totalPtsWon[idPlayerKnockedUpon] += (value + 25);
								_cat.debug("undercut total pts are " + totalPtsWon[idPlayerKnockedUpon] + " for player : " + idPlayerKnockedUpon);
							}
							else {
								//no undercut.
								int value = _players[idPlayerKnockedUpon].getRUMMYPoints() - _players[idPlayerKnocking].getRUMMYPoints();
								//if spades is first card, then double up
								if (doubleUpSpades)
									value *= 2;
								
								strRes[idPlayerKnockedUpon] = (roundNo + 1) + "'" + idPlayerKnockedUpon + "'" +
										_players[idPlayerKnockedUpon].getRUMMYPoints()
										+ "'0'0'" + totalPtsWon[idPlayerKnockedUpon]
												+ "'1";
								if (!roundWinners[idPlayerKnockedUpon].isEmpty())
									roundWinners[idPlayerKnockedUpon] += "|" + strRes[idPlayerKnockedUpon];
								else
									roundWinners[idPlayerKnockedUpon] = strRes[idPlayerKnockedUpon];
								
								_currRoundResStr += strRes[idPlayerKnockedUpon] + "|";
								
								totalPtsWon[idPlayerKnocking] += (value);
								
								_cat.debug("knocked total pts are " + totalPtsWon[idPlayerKnockedUpon] + " for player : " + idPlayerKnockedUpon);
								
								_cat.debug("knocking total pts are " + totalPtsWon[idPlayerKnocking] + " for player : " + idPlayerKnocking);
								
								valEarned += value;
								
								bonusCnt += 10;
							}
							
							resStr += roundWinners[idPlayerKnockedUpon] + "`";
						}
//					}
					
					if (knockingPersonWon){
						//beat every player. hoo hoo. deserve it
						roundWonCount[idPlayerKnocking]++;
						idPlayerValidDeclared = idPlayerKnocking;
					}
					
					//this is the knocking bonus.
					totalPtsWon[idPlayerKnocking] += bonusCnt;
					//now for id player knocking
					strRes[idPlayerKnocking] = (roundNo + 1) + "'" + idPlayerKnocking+ "'" + 
							_players[idPlayerKnocking].getRUMMYPoints() + "'" + valEarned
							+ "'" + bonusCnt + "'" + totalPtsWon[idPlayerKnocking] + "'1";
					if (!roundWinners[idPlayerKnocking].isEmpty())
						roundWinners[idPlayerKnocking] += "|" + strRes[idPlayerKnocking];
					else
						roundWinners[idPlayerKnocking] = strRes[idPlayerKnocking];
					
					_currRoundResStr += strRes[idPlayerKnocking];
					
					resStr += roundWinners[idPlayerKnocking];
					
					roundNo++;
					
					_roundWinnerName = _players[idPlayerValidDeclared].name;

				} else {
					// case where player declaring gin wins
					int highIndex = idPlayerValidDeclared;
					_roundWinnerName = _players[idPlayerValidDeclared].name;
					
					int[] otherIndex = new int[numPlayersOnTable - 1];//winnerIndex == 0 ? 1 : 0;
					int index = 0;
					int valEarned = 0;
					
					String otherPlayersData = "";
					
					int bonusAmt = 0;
					
					for (int k = 0; k < _players.length; k++){
						if (_players[k] != null && k != highIndex && isActive(_players[k])){
							otherIndex[index++] = k;
							
							double val = _players[k].getRUMMYPoints() * 2;
							//if spades is first card, then double up
							if (doubleUpSpades)
								val *= 2;
							//gin gets the double of deadwood of remaining players
							valEarned += val;
							
							String str2 = (roundNo + 1)
									+ "'" + k + "'" + _players[k].getRUMMYPoints() + "'0'0'"
									+ totalPtsWon[k] + "'0";
							
							if (!roundWinners[k].isEmpty())
								roundWinners[k] += "|" + str2;
							else
								roundWinners[k] = str2;
							
							otherPlayersData += str2 + "|";
							
							_cat.debug("string for loser : " + roundWinners[k]);
							
							bonusAmt += 25;
						}
					}
					
					totalPtsWon[highIndex] += valEarned + bonusAmt;
					
					_cat.debug("total pts : " + totalPtsWon[highIndex] + " , valEarned : " + valEarned + " with bonus");

					String str = (roundNo + 1) + "'"
							+ highIndex + "'0'" + valEarned + "'" + bonusAmt + "'"
							+ totalPtsWon[highIndex] + "'0";

					if (!roundWinners[highIndex].isEmpty())
						roundWinners[highIndex] += "|" + str;
					else
						roundWinners[highIndex] = str;
					
					_cat.debug("string for winner : " + roundWinners[highIndex]);

					roundNo++;
					
					//for gin win, 2 line wins are given.
					roundWonCount[highIndex] += 2;
					
					resStr = "";
					for (int k = 0; k < _players.length; k++){
						if (_players[k] != null && isActive(_players[k])){
							resStr += roundWinners[k] + "`";
						}
					}
					resStr = resStr.substring(0, resStr.length() - 1);
					
					_currRoundResStr = otherPlayersData + str;
					
				} // end of gin player win
			}

			// now take this opportunity to find out if the game is over. this
			// will happen the moment a player's total points go beyond 100
			if (totalPtsWon[0] >= PTS_TO_WIN || totalPtsWon[1] >= PTS_TO_WIN) {
				// game over, roll the ball for end of game
				int winnerIndex = -1;//totalPtsWon[0] >= 100 ? 0 : 1;
				for (int k = 0; k < totalPtsWon.length; k++){
					if (totalPtsWon[k] >= PTS_TO_WIN)
						winnerIndex = k;
				}
				
				_cat.debug("winenrindex : " + winnerIndex + " with amt : " + totalPtsWon[winnerIndex]);
				
				int ptsEarned = 0;
				
				for (int k = 0; k < totalPtsWon.length; k++){
					if (k != winnerIndex && _players[k] != null && isActive(_players[k])){
						ptsEarned += totalPtsWon[winnerIndex] - totalPtsWon[k];
						_cat.debug("ptsearned : " + ptsEarned + " , loser pts : " + totalPtsWon[k] + " , diff : " + (totalPtsWon[winnerIndex] - totalPtsWon[k]));
					}
				}
				
				_cat.debug("pts earned : " + ptsEarned);

				String strBig = "^" + winnerIndex + "'" + ptsEarned + "'150";

				// give a bonus of 150
				ptsEarned += PTS_TO_WIN;

				// now determine the line bonus, 25 for every round win
//				int winLineBonus = 0, totalLoserBonus = 0;
//				for (int k = 0; k < 4; k++){
//					if (totalPtsWon[k] > 0){
//						int linebonus = 0;
//						if (roundWonCount[k] > 0){
//							linebonus = 25 * roundWonCount[k];
//							strBig += k + "-" + linebonus + "~";
//							
//							if (k == winnerIndex)
//								winLineBonus = linebonus;
//							else {
//								totalLoserBonus += linebonus;
//							}
//						}
//					}
//				}
//				strBig = strBig.substring(0, strBig.length() - 1);
				
				//this could be -ve. be prepared for it
//				ptsEarned += winLineBonus - totalLoserBonus;

//				linebonus2 = roundWonCount[otherIndex] * 25;
//
//				ptsEarned += linebonus - linebonus2;
//				if (winnerIndex == 0)
//					strBig += winnerIndex + "-" + linebonus + "~" + otherIndex + "-" + linebonus2;
//				else 
//					strBig += otherIndex + "-" + linebonus2 + "~" + winnerIndex + "-" + linebonus;
				
				resStr += strBig;

				declareGameOver(winnerIndex, ptsEarned, resStr);
			}

			return resStr;
		}

		// to be called when game is well and truly over -- when plyr count < 2
		// and when one player's win amt >= 100
		public void declareGameOver(int posWinner, int totalWinPts,	String winnerString) {
			_cat.debug("pos win: " + posWinner + ", total : " + totalWinPts + " , winstr : " + winnerString);
			
			_nextMoveExpTime = System.currentTimeMillis();
			_gameOngoing = false;
			_winnerPos = posWinner;
			_winnerString = winnerString;
			roundNo = 0;
			_nextMovePlayer = -1;

			double winamt = totalWinPts * POINTSPERRUPEE;
			_rake = winamt * RAKE_PERCENTAGE / 100;
			winamt -= _rake;
			_players[_winnerPos].setRUMMYWinAmt(totalWinPts);
			_players[_winnerPos].getPresence().addToWin(winamt);
			_players[_winnerPos].setRUMMYMovesStr("&WinPoints^" + totalWinPts);
			//now for both, add winner info
			_players[_winnerPos].setRUMMYMovesStr("&Winner^" + _players[_winnerPos].getName());
			
			int otherPos = _winnerPos == 0 ? 1 : 0;

			// now make the losing player pay for it all
//			for (int k = 0; k < idPlayerKnockedUpon.length; k++){
				if (otherPos != -1){
					
					_players[otherPos].getPresence().currentRoundBet(winamt + _rake);
					_players[otherPos].setRUMMYMovesStr("&Penalty^-" + totalWinPts);
					_amtWonString += otherPos + "'" + ((winamt + _rake) * -1) + "|";
				}
//			}
			
			_amtWonString += _winnerPos + "'" + winamt;
			// hack - comment later
			_cat.debug("winners win amt computd");
			dbwinamt = winamt;
			
			//so that sit out and sit in can be shown at the earliest
			handleSitoutSitin();
			
			new Thread(){
				public void run(){
					// loop thru the players, find the participating players
					// insert their entries in t_player_per_grs
					GameRunSession grs = new GameRunSession(_gid, rummygrid,GameType.RummyOklaGin2);
//							_type.intVal());
					grs.setEndTime(new Date());
					grs.setStartTime(_gameStartTime.getTime());
					double rake[];
					rake = Utils.integralDivide(_rake, 2);

					// ************STARTING BATCH
					int j = -1;
					try {
						Statement grs_stat = grs.startBatch();
						for (int i = 0; i < MAX_PLAYERS; i++) {
							// _playersInHand
							if (_players[i] == null || !isActive(_players[i]))
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

							grs.setMoveDet(pr.getRUMMYMovesStr());
							grs.save(grs_stat);

							// now set the player's start worth
							_players[i].setGameStartWorth(grs.getEndWorth());
						}
						// COMMITTING BATCH
						grs.commitBatch(grs_stat);
						_cat.debug("grs committed...");
					}
					catch (Exception ex) {
						_cat.debug("Exception - " + ex.getMessage());
					}
				}
			}.start();
			
			//for t_user_eap, this is the only place
			new Thread(){
				public void run(){
					double rake[];
					rake = Utils.integralDivide(_rake, 2);
					
					for (int m = 0; m < 2; m++){
						if (_players[m] == null)
							continue;
						DBEAP dbeap = new DBEAP(_players[m].name, rake[m]);
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
			
			if (!_gameOngoing){
				_cat.debug("game not started. someone leaving before.");
				if (getCountTotalPlayers() > 0)
					broadcastMessage(-1);
				return true;//don't want to send message from handlemovenoaction
			}
			
			int cntActPlyrs = getCntStatusActivePlayers();
			if (cntActPlyrs < 2) {
				// game can't proceed, the lone ranger is the winner
				if (cntActPlyrs >= 1) {
					int pos = getOnlyActivePos();
					if (pos != -1 && _gameOngoing) {
						
						if (_players[pos].getUsingTimeBank()){
							_players[pos].setTimeBank(_players[pos].getTimeBankExpTime() - System.currentTimeMillis());
							_players[pos].setUsingTimeBank(false);
							_cat.debug("cgo pos " + pos + " and its time bank using : " + _players[pos].getUsingTimeBank());
						}
						
						if (errorCode <= 0)
							errorCode = 4;
						
						// only one player, so end the entire game here
						int winnerIndex = pos;
						int otherIndex = pos == 0 ? 1 : 0;
						int valEarned = 0;
						int tempvalEarned = computePoints2(_players[otherIndex]._allotedCards);
						String str2 = (roundNo + 1) + "'" + otherIndex + "'" + tempvalEarned
								+ "'0'0'" + totalPtsWon[otherIndex]
								+ "'" + errorCode;
						
						if (!roundWinners[otherIndex].isEmpty())
							roundWinners[otherIndex] += "|" + str2;
						else
							roundWinners[otherIndex] = str2;

						valEarned += tempvalEarned;
						
						_cat.debug("cgo for player : " + otherIndex + " total pts is " + totalPtsWon[otherIndex]);
						
						idPlayerValidDeclared = winnerIndex;
						totalPtsWon[idPlayerValidDeclared] += valEarned;
						String str = (roundNo + 1)
								+ "'" + winnerIndex + "'0'" + valEarned
								+ "'0'" + totalPtsWon[winnerIndex] + "'" + errorCode;
						if (!roundWinners[winnerIndex].isEmpty())
							roundWinners[winnerIndex] += "|" + str;
						else
							roundWinners[winnerIndex] = str;
						
						int ptsEarned = 0;
						
						for (int k = 0; k < totalPtsWon.length; k++){
							if (_players[k] != null && k != winnerIndex && (isActive(_players[k]) || _players[k]._allotedCards != null)){
								ptsEarned += totalPtsWon[winnerIndex] - totalPtsWon[k];
							}
						}

//						String strBigr = "";
//						int winLineBonus = 0, totalLoserBonus = 0;
//						for (int k = 0; k < totalPtsWon.length; k++){
//							if (totalPtsWon[k] > 0){
//								int linebonus = 0;
//								if (roundWonCount[k] > 0){
//									linebonus = 25 * roundWonCount[k];
////									strBigr += k + "-" + linebonus + "~";
//									
//									if (k == winnerIndex)
//										winLineBonus = linebonus;
//									else {
//										totalLoserBonus += linebonus;
//									}
//								}
//							}
//						}
//						if (!strBigr.isEmpty())
//							strBigr = strBigr.substring(0, strBigr.length() - 1);
						
						//this could be -ve. be prepared for it
						// give a bonus of 150
						//now take care of line bonus
//						ptsEarned += winLineBonus - totalLoserBonus;
						String strBig = "^" + winnerIndex + "'" + ptsEarned
								+ "'150";// 150 is the game win bonus

						String resStr = roundWinners[winnerIndex] + "`";
						_currRoundResStr = str + "|" + str2;
						if (otherIndex != -1) {
							resStr += roundWinners[otherIndex];
						}
						
						resStr += strBig;
						
						_roundWinnerName = _players[winnerIndex].name;
						
						ptsEarned += PTS_TO_WIN;
						
						declareGameOver(winnerIndex, ptsEarned, resStr);

						broadcastMessage(pos);
					} else {
						// for some reaons either game is not on or pos is -1
						_gameOngoing = false;
						removePlayersDisconnected();
					}
				} else {
					// less than 1 player on table - sad state of affairs
					_gameOngoing = false;
					removePlayersDisconnected();
				}

				return true;
			} else {
				return false;
			}
		}

		void removePlayersDisconnected() {
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null) {
					// check if player is marked as active - then let it be.
					// else remove him
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
		Card[] removeAllCardsOfRun2(String cards, String runs) {
			String[] toks1 = cards.split("\\|", -1);
			// toks1 has the groups now. the indices to be removed are there in runs
			String[] runToks1 = runs.split("\\'");
			int[] runTks = new int[runToks1.length];
			for (int i = 0; i < runTks.length; i++) {
				runTks[i] = Integer.parseInt(runToks1[i]);
			}

			int lenRetCards = 0;
			String retCards = "";
			for (int i = 0; i < toks1.length; i++) {
				if (toks1[i].isEmpty())
					continue;
				
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
						// _cat.debug("cards Pending : " + toks2[k]);
					}
				}

				if (indexRetCards > 0)
					return toRetCards;
				else
					return null;
			}

			return null;
		}

		boolean determineIfJoker(Card crd) {
			// always return false - no joker in this game
			return false;
		}

		boolean CheckIfSets(String[] toks2) {
			_cat.debug("checking sets with joker or no joker");
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

			if (cardTwo[toks2.length - 1] % 13 == Card.ACE) {
				runpure = false;
			} else {
				for (int i = 1; i < toks2.length; i++) {
					if (beginIndex + 1 != cardTwo[i]) {
						runpure = false;
						break;
					}
					beginIndex = cardTwo[i];
				}
			}

			if (!runpure) {
				// check for the case of Ace, Two, Three et al. for these cases
				// as Ace is at the end the condition will fail
				if (cardTwo[toks2.length - 1] % 13 == Card.ACE) {
					runpure = true;
					_cat.debug("checknig pure run wiht Ace");
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

		// boolean CheckImPureRuns(String[] toks2) {
		// _cat.debug("impure run check");
		// int playerCount = 0, jokerCount = 0;
		// Card[] playerCards = new Card[toks2.length];
		//
		// for (int i = 0; i < toks2.length; i++) {
		// Card cr = new Card(toks2[i]);
		// if (determineIfJoker(cr))
		// jokerCount++;
		// else {
		// playerCards[playerCount++] = cr;
		// }
		// }
		//
		// if (playerCount == 1 && jokerCount >= 2)
		// return true;
		// if (playerCount == 0)
		// return false;
		//
		// // there will be jokers and they will be completing the run.
		// // find the total gap that needs to be covered by jokers. then check
		// // if you have that many jokers in the group
		// // if so, then balle balle. if not, then what yaar, try next one!
		//
		// ArrayList<Integer> cardsArr = new ArrayList();
		// for (int index = 0; index < playerCount; index++) {
		// cardsArr.add(playerCards[index].getIndex());
		// }
		// Collections.sort(cardsArr);
		//
		// int suitRun = -1;
		// int[] cardTwo = new int[playerCount];
		// for (int i = 0; i < playerCount; i++) {
		// // these cards have to be of same suit or else no run is
		// // possible - brouhaahaha
		// cardTwo[i] = cardsArr.get(i);
		// if (suitRun == -1)
		// suitRun = cardTwo[i] / 13;
		// else {
		// int newsuit = cardTwo[i] / 13;
		// if (suitRun != newsuit)
		// return false;// cards not of same suit, nuff said
		// }
		// }
		//
		// // the cardTwo array has sorted cards - if this is a pure run, the
		// // cards have to be in ascending order
		// int jokerNeeded = 0;
		// int beginIndex = cardTwo[0];
		// for (int i = 1; i < playerCount; i++) {
		// if (beginIndex + 1 != cardTwo[i]) {
		// // we need joker or jokers to move from beginIndex to
		// // cardTwo[i]
		// int value = cardTwo[i] - beginIndex - 1;
		// // this is a very special case - suppose there are 2 of 10S
		// // and
		// // we have a joker
		// // our logic will give value as -1 and that is less than 1
		// // so it
		// // will treat it as a valid sequence
		// // not now.
		// if (value < 0)
		// return false;
		//
		// jokerNeeded += value;
		// }
		// beginIndex = cardTwo[i];
		// }
		//
		// if (jokerNeeded <= jokerCount)
		// return true;
		// else {
		// // there is a special case of Ace where it can be used with Two
		// // and Three and others to make a run
		// if (cardTwo[playerCount - 1] % 13 == Card.ACE) {
		// _cat.debug("checknig impure run wiht Ace");
		// // last card is an Ace - go for it
		// jokerNeeded = 0;
		// beginIndex = -1;
		// for (int i = 0; i < playerCount - 1; i++) {
		// if (beginIndex + 1 != cardTwo[i] % 13) {
		// // we need joker or jokers to move from beginIndex
		// // to cardTWo[i]
		// int indCard = cardTwo[i] % 13;
		// int indCard2 = beginIndex + 1;
		// int val = indCard - indCard2;
		// if (val < 0)
		// return false;
		//
		// jokerNeeded += val;
		// }
		// beginIndex = cardTwo[i] % 13;
		// }
		//
		// if (jokerNeeded <= jokerCount)
		// return true;
		// else
		// return false;
		// }
		//
		// return false;
		// }
		// }

		int findPenalty(String[] toks) {
			Card[] cards = new Card[toks.length];
			for (int i = 0; i < toks.length; i++) {
				cards[i] = new Card(toks[i]);
			}

			return computePoints2(cards);
		}

		String getValidMelds(String cardsstr) {
			String allRunsSetsGroup = "";

			String[] toks1 = cardsstr.split("\\|");
			for (int i = 0; i < toks1.length; i++) {
				boolean somethingfound = false;
				_cat.debug("to check : " + toks1[i]);
				String[] toks2 = toks1[i].split("\\`");
				// got the cards of one group
				// there can be these cases - a pure run, an impure run, a pure
				// set, an impure set, a tanala
				// if (toks2.length == 3) {
				// // could be a tanala
				// if (toks2[0].compareToIgnoreCase(toks2[1]) == 0
				// && toks2[0].compareToIgnoreCase(toks2[2]) == 0) {
				// tanalaCount++;
				// allRunsSetsGroup += toks1[i] + "'";
				// somethingfound = true;
				// }
				// }

				if (toks2.length >= 3 && !somethingfound) {
					// check for run
					if (CheckPureRuns(toks2)) {
						allRunsSetsGroup += toks1[i] + "'";
						somethingfound = true;
					}
					// else if (CheckImPureRuns(toks2)) {
					// allRunsSetsGroup += toks1[i] + "'";
					// somethingfound = true;
					// }

					if (!somethingfound
							&& (toks2.length == 3 || toks2.length == 4)) {
						// could be a set -. take care. there could be a joker
						// here
						if (CheckIfSets(toks2)) {
							allRunsSetsGroup += toks1[i] + "'";
							somethingfound = true;
						}
					}
				}
			}

			if (!allRunsSetsGroup.isEmpty()) {
				allRunsSetsGroup = allRunsSetsGroup.substring(0,
						allRunsSetsGroup.length() - 1);
			}
			return allRunsSetsGroup;
		}

		String countRunsAsArranged(String cardsstr) {
			int pureRuns = 0, impureRuns = 0, numSets = 0, tanalaCount = 0;
			String allRunsSetsGroup = "";

			String[] toks1 = cardsstr.split("\\|", -1);
			for (int i = 0; i < toks1.length; i++) {
				boolean somethingfound = false;
				_cat.debug("to check : " + toks1[i]);
				if (toks1[i].isEmpty())
					continue;
				
				String[] toks2 = toks1[i].split("\\`");
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
					// else if (CheckImPureRuns(toks2)) {
					// impureRuns++;
					// allRunsSetsGroup += i + "'";
					// somethingfound = true;
					// _cat.debug("impure run : " + toks1[i]);
					// }

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

		int checkValidCards2(Card[] cards, String cardsStrFrmClnt) {

			int resTemp = -1;

			if (cardsStrFrmClnt.isEmpty()) {
				resTemp = computePoints2(cards);
				return resTemp;
			}

			String restrVal = countRunsAsArranged(cardsStrFrmClnt);
			if (restrVal != null && !restrVal.isEmpty()) {
				// we do have 1st life.
				// we have 1st life but no second life - remove cards of
				// pure runs
				_cat.debug("restrval : " + restrVal);
				
				char last = restrVal.charAt(restrVal.length()-1);
				String test = last + "";
				if (test.compareToIgnoreCase("'") == 0)
					restrVal = restrVal.substring(0, restrVal.length() - 1);
				
				Card[] cardsPenal = removeAllCardsOfRun2(cardsStrFrmClnt,
						restrVal);
				
				if (cardsPenal != null){
					for (int i = 0; i < cardsPenal.length; i++) {
						if (cardsPenal[i] == null)
							continue;
						System.out.print(" : " + cardsPenal[i] + " ");
					}
//					_cat.debug();
				}

				return computePoints2(cardsPenal);
			} else {
				// no melds - penalty on all cards
				resTemp = computePoints2(cards);
				return resTemp;
			}
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

				dummy = cards[i].getHighBJRank();
				if (dummy >= 11)
					dummy = 1;
				else if (dummy == 0)
					dummy = 10;
				val += dummy;
			}

			if (val > 500)
				val = 500;// this is the max that a player has to pay
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
						&& (_nextMovePlayer == 111 || (_nextMovePlayer != -1 && idPlayerKnocking != -1))
						&& (System.currentTimeMillis() - _nextMoveExpTime) > 21000) { // should
																						// be
																						// 65
																						// seconds
					noMovesToBeAccepted = true;
					errorCode = -1;
					if (idPlayerKnocking != -1) {
						int index = 0;
						for (int k = 0; k < 2; k++){
							if (_players[k] != null && k != idPlayerKnocking && isActive(_players[k]))
								idPlayerKnockedUpon = k;
						}
					}
					
					_cat.debug("called from run ----------------------------");
					_lastMovePos = -1;
					_lastMove = move_RUMMY_INVALID;
					_lastMoveString = "TimedOut";
					
					//if someone knocked or did gin, finish game else go for draw
					if (idPlayerValidDeclared != -1 || idPlayerKnocking != -1)
						_drawString = declareRoundOver(111);
					else 
						_drawString = declareRoundOver(-1);
					
					broadcastMessage(-1);
					idPlayerValidDeclared = -1;
					_nextMovePlayer = -1;
					noMovesToBeAccepted = false;
				}

//				if (_gameOngoing
//						&& _nextMovePlayer == 222
//						&& (System.currentTimeMillis() - _nextMoveExpTime) > 90000) { // should
//																						// be
//																						// 65
//																						// seconds
//					noMovesToBeAccepted = true;
//					errorCode = -1;
//					//check if the players are still on table - if not then end the game
//					if (!checkGameOver()) {
//						_drawString = declareRoundOver(222);
//						broadcastMessage(-1);
//						idPlayerValidDeclared = -1;
//						_nextMovePlayer = -1;
//						noMovesToBeAccepted = false;
//					}
//				}

				// now check if someone has timed out his moves
				if (_gameOngoing && idPlayerKnocking == -1
						&& (System.currentTimeMillis() - _nextMoveExpTime) > 30000) { // 65,
																						// 1500
																						// for
																						// 300
																						// secs
					noMovesToBeAccepted = true;
					errorCode = -1;
					if (_nextMovePlayer != -1)
					{
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
							handleTimedOut();
					}
					noMovesToBeAccepted = false;
				}

				// maintenance job - shall we create a separate thread for it?
				// maintenance job - if there is only one player on a table
				for (int m = 0; m < MAX_PLAYERS && !_gameOngoing; m++) {
					if (_players[m] != null
							&& System.currentTimeMillis()
									- _players[m].rummyLeftTableTime > 7000
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

						_players[m] = null;
						
						if (getCountTotalPlayers() > 0){
							resetTable();
							broadcastMessage(-1);
						}
					}
				}

				// start new round after 3 seconds of result display
				if (_gameOngoing
						&& _nextMovePlayer == -1
						&& getCurrentPlayersCount() >= 2
						&& (System.currentTimeMillis() - _nextMoveExpTime) > 8000) {
					_cat.debug("from run method, already 8 secs elapsed.");
					initGame();
					startNewRound();
				}
				
				//error condition - for some reason a player left in the middle of game. end the game now.
				if (_gameOngoing
						&& _nextMovePlayer == -1
						&& getCurrentPlayersCount() < 2
//						&& !fixingDealerNextHand
						) {
					
					_cat.debug("from run method, less than 2 players on table!!!!!!!!!!!!!!!!!");
					checkGameOver();
					initGame();
				}

				if (!_gameOngoing && _nextMoveExpTime != -1
						&& System.currentTimeMillis() - _nextMoveExpTime > 8000) {
					int countPlayers = getCountActivePlayers() + getCountSitInReqPlayers();
					
//					_cat.debug("total : " + getCountTotalPlayers() + ", count: " + countPlayers + ", counter : " + counterGameNotStarted);
					if (getCountTotalPlayers() >= 2 && counterGameNotStarted < 8) {
						_cat.debug("from run method, calling fixdealerfirst, 8 secs elapsed........ " + counterGameNotStarted);
						if (countPlayers >= 2)
							fixDealerFirst();
						else {
							counterGameNotStarted++;
							initGame();
							
							if (counterGameNotStarted >= 5)
								resetTable();
							
							if (countPlayers == 1)
								broadcastMessage(-1);
						}
					} else {
						// clear out the jokers for it creates a wrong
						// impression
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
						if (System.currentTimeMillis() - _nextMoveExpTime > 60000) { //orig was 20 seconds
							for (int m = 0; m < MAX_PLAYERS; m++) {
								if (_players[m] != null) {
									sendMessageKickedOut(m, 2);
									// remove the player
									_players[m] = null;
								}
							}
							counterGameNotStarted = 0;
							_nextMoveExpTime = -1;
						}//end of if time
					}
				}

				if (!_gameOngoing) {
					if (System.currentTimeMillis() - _nextMoveExpTime > 60000) {
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
			int rand = Rng.nextIntBetween(0, MAX_CARDS);
			while (_deck.get(rand)) {
				rand = Rng.nextIntBetween(0, MAX_CARDS);
			}
			_deck.set(rand);

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

		public int adjustCardsArray(RummyProfile prp, int pos, String type) {
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
						return -1;
					}
				}
				// get rid of card - index is in type
				int[] newCards = new int[maxCardsToPlay];
				int j = 0;
				boolean found = false;
				for (int i = 0; i < maxCardsToPlay + 1; i++) {
					if (prp._allotedCards[i].getIndex() != typeIndex || found) {
						newCards[j++] = prp._allotedCards[i].getIndex();
					} else {
						found = true;
					}
				}
				prp._allotedCards = new Card[maxCardsToPlay];
				for (int i = 0; i < newCards.length; i++) {
					Card crd = new Card(newCards[i]);
					crd.setIsOpened(true);
					prp._allotedCards[i] = crd;
				}
			} else {
				// check if teh player is deliberately sending declare move
				if ((prp.getRUMMYStatus() & status_ACTIVE) != status_ACTIVE) {
					return -1;
				} else {
					sendErrorMessage(pos, 1);
					return -1;
				}
			}

			return typeIndex;
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
				
				if(!_gameOngoing){
					handleSitoutSitin();
				}
				
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
				
				if(!_gameOngoing){
					handleSitoutSitin();
				}
				
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
			if (_nextMovePlayer == 111 && moveId != move_RUMMY_ADJUST_LOSER) {
				_cat.debug("no moves other than declare loser allowed!!!");
				sendErrorMessage(pos, 5);
				return;
			} else if (_nextMovePlayer == 222 && moveId != move_RUMMY_DRAW) {
				_cat.debug("no moves other than draw allowed!!!");
				sendErrorMessage(pos, 3);
				return;
			} else {
				if (_nextMovePlayer != 111 && pos != _nextMovePlayer && _nextMovePlayer != 222) {
					_cat.debug("no moves allowed from this pos!!!");
					sendErrorMessage(pos, 0);
					return;
				}

				// for PASS, add code here

				if ((moveId & _nextMovesAllowed) != moveId
						&& _nextMovePlayer != 111 && _nextMovePlayer != 222) {
					_cat.debug("these moves not allowed from this pos!!!");
					sendErrorMessage(pos, 0);
					return;
				}
			}

			_players[pos].rummyLeftTableTime = -1;

			// update the rummyLastMoveTime for this player
			_players[pos].rummyLastMoveTime = System.currentTimeMillis();

//			if (moveId == move_RUMMY_DRAW) {
//				_lastMove = move_RUMMY_DRAW;
//				_lastMovePos = pos;
//				_lastMoveString = "GameDeclareDraw";
//				prp.setRUMMYMovesStr("&Draw^" + cardsDet);
//				prp.setRUMMYMoveId(move_RUMMY_DRAW);
//				
//				_nextMovePlayer = 222;
//
//				// just store the cards arrangement string from player
//				prp.cardsStrFromClient = cardsDet;
//
//				countPlayerResponseDeclareLoser--;
//				if (countPlayerResponseDeclareLoser <= 0) {
//					// end the game here
//					_drawString = declareRoundOver(222);
//					broadcastMessage(-1);
//					idPlayerValidDeclared = -1;
//					_nextMovePlayer = -1;
//				}
//			}

			if (moveId == move_RUMMY_ADJUST_LOSER) {
				//put some conditions here.
				if (_nextMovePlayer != 111)
					return;
				
				if (pos == idPlayerKnocking || pos == idPlayerValidDeclared)
					return;
				
				_nextMoveExpTime = System.currentTimeMillis();
				_lastMove = move_RUMMY_ADJUST_LOSER;
				_lastMovePos = pos;
				_lastMoveString = "GameDeclare'SubmitHand";
				prp.setRUMMYMovesStr("&Adjust^" + cardsDet);
				prp.setRUMMYMoveId(move_RUMMY_ADJUST_LOSER);
				
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " sent final cards.";

				// just store the cards arrangement string from player
				prp.cardsStrFromClient = cardsDet;

				int result = checkValidCards2(prp._allotedCards,
						prp.cardsStrFromClient);
				prp.setRUMMYPoints(result);
				
				//all players have to share their cards. then only the game can be ended
				countPlayerResponseDeclareLoser--;
				if (countPlayerResponseDeclareLoser <= 0){
					_drawString = declareRoundOver(111);
					broadcastMessage(-1);
					idPlayerValidDeclared = -1;
					idPlayerKnockedUpon = -1;
					idPlayerKnocking = -1;
					// end the game here
					_nextMovePlayer = -1;
				}
			}

			if (moveId == move_RUMMY_KNOCK) {
				_lastMove = move_RUMMY_KNOCK;
				_lastMovePos = pos;
				_lastMoveString = "GameDeclare'";
				prp.setRUMMYMovesStr("&Knock^"+ cardsDet);
				prp.setRUMMYMoveId(move_RUMMY_KNOCK);
				_nextMoveExpTime = System.currentTimeMillis();
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);
				
				// check if cards are indeed valid
				boolean flagGameOver = false;
				int typeIndex = adjustCardsArray(prp, pos, type);

				if (typeIndex == -1)
					return;
				
				_cat.debug("reaching here, typeindex : " + typeIndex);
				
				isGin = 1;

				idPlayerKnocking = pos;
				prp.cardsStrFromClient = cardsDet;
				int result = checkValidCards2(prp._allotedCards,
						prp.cardsStrFromClient);
				
				_cat.debug("penalyt : " + result);
				
				_discardCard = new Card(typeIndex);
				_discardCard.setIsOpened(true);
				_discardDeck[_indexDiscardDeck++] = _discardCard;

				if (result <= KNOCKING_POINTS_2)
					flagGameOver = true;

				if (flagGameOver) {
					if (idPlayerKnocking != -1){
						int index = 0;
						for (int k = 0; k < 2; k++){
							if (_players[k] != null && k != idPlayerKnocking && isActive(_players[k]))
								idPlayerKnockedUpon = k;
						}
					}
					
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " knocked.";
					
					_cat.debug("game over....");
					_lastMoveString += "Valid:Winner";
					prp.setRUMMYPoints(result);
					prp.setRUMMYMovesStr("^Valid~" + result);

					// Critical - if a player knocks, its melds have to travel
					// to opponent
					_knockingCardStr = getValidMelds(prp.cardsStrFromClient);
					
					_cat.debug("melded cards of knocking player : " + _knockingCardStr);

					// for each player, now set the move as declare loser. now
					// keep a count of players from whom messages have to come
					_nextMovesAllowed = move_RUMMY_ADJUST_LOSER;
					_nextMovePlayer = 111;//getNextActivePos(pos);
					broadcastMessage(-1);

					// no other player to wait on - end this drama now.
					// now keep a track of how many active players are going to
					// respond back
					for (int m = 0; m < MAX_PLAYERS; m++) {
						if (_players[m] != null
								&& m != pos
								&& (isActive(_players[m]) && !isRemoved(_players[m]))) {
							// only non folded players are supposed to make
							// moves
							countPlayerResponseDeclareLoser++;
						}
					}

					if (countPlayerResponseDeclareLoser == 0) {
						// no other player to wait on - end this drama now.
						checkGameOver();
					}

					return;
				} else {
					// serious mistake on client side, can't come here
					prp.setRUMMYMovesStr("^Invalid~" + result);
					sendErrorMessage(pos, 4);
					_nextMovePlayer = getNextActivePos(_nextMovePlayer);
					_nextMovesAllowed = move_RUMMY_NEWCARD;
					_lastMoveString = "DiscardCard:" + type;
					broadcastMessage(-1);
					return;
				}
			}

			if (moveId == move_RUMMY_DECLAREGIN) {
				_lastMove = move_RUMMY_DECLAREGIN;
				_lastMovePos = pos;
				_lastMoveString = "GameDeclare'";
				prp.setRUMMYMovesStr("&GinRummy^"+cardsDet);
				prp.setRUMMYMoveId(move_RUMMY_DECLAREGIN);
				_nextMoveExpTime = System.currentTimeMillis();
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);

				// check if cards are indeed valid
				boolean flagGameOver = false;
				int typeIndex = adjustCardsArray(prp, pos, type);

				if (typeIndex == -1)
					return;
				
				_cat.debug("typeindex : " + typeIndex);
				
				isGin = 2;
				
				_knockingCardStr = getValidMelds(prp.cardsStrFromClient);

				idPlayerKnocking = -1;
				prp.cardsStrFromClient = cardsDet;
				int result = checkValidCards2(prp._allotedCards,
						prp.cardsStrFromClient);
				_cat.debug("penalyt : " + result);
				
				_discardCard = new Card(typeIndex);
				_discardCard.setIsOpened(true);
				_discardDeck[_indexDiscardDeck++] = _discardCard;

				if (result == 0)
					flagGameOver = true;

				if (flagGameOver) {
					_cat.debug("gin is correct call.");
					_lastMoveString += "Valid:Winner";
					idPlayerValidDeclared = pos;
					prp.setRUMMYPoints(result);
					prp.setRUMMYMovesStr("^Valid~" + result);
					
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " has gin.";
					
					// for each player, now set the move as declare loser. now
					// keep a count of players from whom messages have to come
					_nextMovesAllowed = move_RUMMY_ADJUST_LOSER;
					_nextMovePlayer = 111;//getNextActivePos(pos);
					broadcastMessage(-1);

					// no other player to wait on - end this drama now.
					// now keep a track of how many active players are going to
					// respond back
					for (int m = 0; m < MAX_PLAYERS; m++) {
						if (_players[m] != null
								&& m != pos
								&& (isActive(_players[m]) && !isRemoved(_players[m]))) {
							// only non folded players are supposed to make
							// moves
							countPlayerResponseDeclareLoser++;
						}
					}

					if (countPlayerResponseDeclareLoser == 0) {
						// no other player to wait on - end this drama now.
						checkGameOver();
					}

					return;
				} else {
					// serious mistake on client side, can't come here
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " made invalid gin.";
					
					prp.setRUMMYMovesStr("^Invalid~" + result);
					sendErrorMessage(pos, 4);
					_nextMovePlayer = getNextActivePos(_nextMovePlayer);
					_nextMovesAllowed = move_RUMMY_NEWCARD;
					_lastMoveString = "DiscardCard:" + type;
					broadcastMessage(-1);
					return;
				}
			}

			if (moveId == move_RUMMY_NEWCARD) {
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
				if (prp._allotedCards.length >= maxCardsToPlay + 1) {
					System.out
							.println("already gave one card, how many does it want more !!!");
					sendErrorMessage(pos, 0);
					return;
				}

				prp.setRUMMYMoveId(move_RUMMY_NEWCARD);
				prp.setRUMMYStatus(status_ACTIVE);
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
							sendErrorMessage(pos, 0);
							return;
						}
					}
					
					_discardDeck[_indexDiscardDeck - 1] = null;
					_indexDiscardDeck--;
					
					_lastMoveString = "ChoseDiscardedCard";
					prp.setRUMMYMovesStr("&GetDisc^" + _discardCard.toString());
					
					if (_indexDiscardDeck > 0)
						_discardCard = _discardDeck[_indexDiscardDeck - 1];
					else
						_discardCard = null;

					newCardAdded = cr.toString();
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " chose discarded card.";
				}

				_cat.debug("came here : " + _lastMoveString
						+ " , card : " + cr.toString());
				
				prp._firstMoveDone = true;

				Card[] clonedCards = prp._allotedCards.clone();
				prp._allotedCards = new Card[maxCardsToPlay + 1];
				for (int i = 0; i < maxCardsToPlay; i++) {
					prp._allotedCards[i] = clonedCards[i];
				}
				prp._allotedCards[maxCardsToPlay] = cr;

				// no need to change player
				if (!checkGameOver()) {
					_nextMovesAllowed = move_RUMMY_DISCARD | move_RUMMY_KNOCK
							| move_RUMMY_DECLAREGIN;
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
				int[] newCards = new int[maxCardsToPlay];
				int j = 0;
				boolean found = false;
				for (int i = 0; i < maxCardsToPlay + 1; i++) {
					if (prp._allotedCards[i].getIndex() != typeIndex || found) {
						newCards[j++] = prp._allotedCards[i].getIndex();
					} else {
						found = true;// found 1st instance of this card being
										// removed. if there are 2 cards and
										// player wants to get rid of 1, then
										// other should stay.
					}
				}
				prp._allotedCards = new Card[maxCardsToPlay];
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

				// place a check here for the fresh stock pile. if only 2 cards
				// are there, declare round over
				boolean flagMsgSent = false;
				if (_deck.cardinality() >= MAX_CARDS) {//should be max_cards - 2 but for testing letting it to be max_cards itself
					System.out
							.println("WARNING!!!! fresh pile has run out!!!!");
					addCardsToFreshPile(_discardDeck, _indexDiscardDeck);
					_discardDeck = new Card[65];

					// keep the same discard card - don't change it
					_deck.set(_discardCard.getIndex());

					_indexDiscardDeck = 0;
					_discardDeck[_indexDiscardDeck++] = _discardCard;

					// keep a counter here
					_trackNumTimesDiscardPileReused++;
					if (_trackNumTimesDiscardPileReused >= 1) {
						// time to end game - we have run thru the fresh card
						// pile twice and yet no result - call for a draw
						_drawString = declareRoundOver(-1);// 
						broadcastMessage(-1);
						
						idPlayerValidDeclared = -1;
						_nextMovePlayer = -1;
						flagMsgSent = true;
					}
				}

				if (!flagMsgSent) {
					if (!checkGameOver()) {
						_nextMovePlayer = getNextActivePos(_nextMovePlayer);
						_nextMovesAllowed = move_RUMMY_NEWCARD;
						broadcastMessage(-1);
					}
				}
			}

			if (moveId == move_RUMMY_PASS) {
				_nextMoveExpTime = System.currentTimeMillis();
				_lastMove = move_RUMMY_PASS;
				_lastMovePos = pos;
				_lastMoveString = "PassedCard";
				prp.setRUMMYMovesStr("&PassFirst^" + _firstCard.toString());
				
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

				if (typeO > 1) {
					_cat.debug("wrong move type for PASS!!!!!");
					sendErrorMessage(pos, 1);
					return;
				}

				prp.setRUMMYMoveId(move_RUMMY_PASS);
				prp.setRUMMYStatus(status_ACTIVE);
				// use type to find if it is frsh pile card (0) or discard (1)
				if (typeO == 0) {
					// don't want the card - it is ok. ask the other man if he
					// wants it
					_nextMovePlayer = getNextActivePos(_nextMovePlayer);
					prp.setRUMMYMovesStr("`Reject");
					
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " passed first card.";

					// if the _nextMovePlayer is our rummy player again, that
					// means the cycle is complete
					// if not, persist with PASS question for the very first
					// card
					if (_nextMovePlayer == _rummyPlayer) {
						_nextMovesAllowed = move_RUMMY_NEWCARD;
						_firstCard = null;
					}
					else
						_nextMovesAllowed = move_RUMMY_PASS;
				} else {
					// banda wants card, give it tya'im.
					_lastMoveString = "ChoseFirstCard";
					prp.setRUMMYMovesStr("`Accept");
					newCardAdded = _firstCard.toString();
					
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " took first card.";

					Card[] clonedCards = prp._allotedCards.clone();
					prp._allotedCards = new Card[maxCardsToPlay + 1];
					for (int i = 0; i < maxCardsToPlay; i++) {
						prp._allotedCards[i] = clonedCards[i];
					}
					
					Card cr = new Card(_firstCard.getIndex());
					cr.setIsOpened(true);
					prp._allotedCards[maxCardsToPlay] = cr;
					_firstCard = null;

					_nextMovesAllowed = move_RUMMY_DISCARD | move_RUMMY_KNOCK
							| move_RUMMY_DECLAREGIN;
					_cat.debug("took the first card");
				}

				if (!checkGameOver()) {
					broadcastMessage(-1);
				}
			}
		}

		public void fixDealerFirst() {
			if (_gameOngoing)
				return;

			// now clear out the all in players list
			resetTable();
			
			if (getCountActivePlayers() < 2){
				//check if any player is sitting out.
				//if so, send message to both players to get that updated
				if (getCountSitOutPlayers() > 0)
					broadcastMessage(-1);
				return;
			}
			
			_cardDealingOrder = "";
			
			_gameOngoing = true;
			
			//do it so that all variables are cleared
			_cat.debug("calling initgame from fixdealerfirst....");
			initGame();
			startNewRound();

			if (true) {

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
						if (_players[m].fixPosCard % 13 == 12){
							//ace is the lowest card
							least = _players[m].fixPosCard % 13;
							leastCardBearer = m; 
						}
						else if (_players[m].fixPosCard % 13 < least) {
							least = _players[m].fixPosCard % 13;
							leastCardBearer = m;
						} 
						
						if (_players[m].fixPosCard % 13 == least) {
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

			_cat.debug("calling startgameifpossible from fixdealerfirst....");
			startGameIfPossible();
		}

		public void startGameIfPossible() {
			// based on sitting players, let us see if we can start a game
			try {
				Thread.currentThread().sleep(1000);
			} catch (InterruptedException ee) {
				// continue
			}

			// game can begin ---
			// now clear out the all in players list
			// resetTable();

			int _countPlayersInit = getCountActivePlayers();
			_cat.debug("startgame - " + _countPlayersInit);
			if (_countPlayersInit >= 2) {
				NUM_DECKS = 1;

				MAX_CARDS = 52 * NUM_DECKS;

				_cat.debug("from startgameifpossible, calling initgame...............");
				initGame();
				
				_amtWonString = "";

				// now initialize the variables
				for (int m = 0; m < MAX_PLAYERS; m++) {
					if (_players[m] != null
							&& !isSittingOut(_players[m])
					// && _players[m].getPresence() != null
					) {
						_players[m]._firstMoveDone = false;
						_players[m]._allotedCards = new Card[maxCardsToPlay];
						_players[m].setRUMMYWinAmt(0);
						_players[m].setRUMMYStatus(status_ACTIVE);
					}
				}

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

				_cardDealingOrder = _rummyPlayer + "'" + _dealer;

				roundWinners[0] = "";
				roundWinners[1] = "";
				
				roundWonCount[0] = 0;
				roundWonCount[1] = 0;

				totalPtsWon[0] = 0;
				totalPtsWon[1] = 0;
				_cat.debug("startgameifpossible : clearing out....");

				_gameStartTime = Calendar.getInstance();

				startNewRound();
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
			
			isGin = 0;

			_winnerString = "";
			_drawString = "";
			_currRoundResStr = "";
			rummygrid = setNextGameRunId();
			_nextMovesAllowed = move_RUMMY_PASS;

			_knockingCardStr = "";
			
			_roundWinnerName = "";
			
			idPlayerValidDeclared = -1;
			idPlayerKnockedUpon = -1;
			idPlayerKnocking = -1;

			//if 2 players, they play with 10 cards
			numPlayersOnTable = getCntStatusActivePlayers();
			if (numPlayersOnTable > 2){
				maxCardsToPlay = 7;
			}
			else {
				maxCardsToPlay = 10;
			}
			
			// give cards to all players
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null && _players[m].getPresence() != null) {
					
					//dealer, rummy joker card, points per rupee, table id
					_players[m].setRUMMYMovesStr("&TID^" + _tid);
					_players[m].setRUMMYMovesStr("&Dealer^" + _dealer);
					_players[m].setRUMMYMovesStr("&PtsPerRupee^" + POINTSPERRUPEE);
					
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
						if (i < maxCardsToPlay - 1)
							_players[m].setRUMMYMovesStr("`");
					}
				}
			}

			broadcastMessage(-1);
			
			try {
			 Thread.currentThread().sleep(12000);
			 } catch (InterruptedException e) {
			 // TODO Auto-generated catch block
			 e.printStackTrace();
			 }
			 
			 _nextMovePlayer = _rummyPlayer;
			 _nextMoveExpTime = System.currentTimeMillis();
			//
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
			temp.append("RummyOklaGin2Server=RummyOklaGin2Server");
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
			
			if (_nextMovePlayer != -1 && _nextMovePlayer < MAX_PLAYERS && _players[_nextMovePlayer] != null && _winnerPos == -1){
				//for time bank
				if (_players[_nextMovePlayer].getUsingTimeBank()){
					temp.append(",DiscProtOn=").append((_players[_nextMovePlayer].getTimeBank()) * 10);
				}
			}
			
			temp.append(",chatMsgOn=").append(chatOn);
			if (!chatMessage.isEmpty()){
				String strcrypt = encrypt(chatMessage);
				temp.append(",chatMsg=").append(strcrypt);
//				_cat.debug(decrypt(strcrypt));
				chatMessage = "";
			}
			else
				temp.append(",chatMsg=");

			if (_discardCard != null)
				temp.append(",DiscardCard=").append(_discardCard.toString());
			else
				temp.append(",DiscardCard=");

			if (_firstCard != null)
				temp.append(",FirstCard=").append(_firstCard.toString());
			else
				temp.append(",FirstCard=");
			
			temp.append(",PtsDoubleSpades=").append(doubleUpSpades);
			
			temp.append(",KnockValue=").append(KNOCKING_POINTS_2);
			
			temp.append(",IsGin=").append(isGin);

			if (idPlayerValidDeclared != -1 && _players[idPlayerValidDeclared] != null) {
				temp.append(",ValidDecPlyr=").append(
						_players[idPlayerValidDeclared].name);
				temp.append(",ValidDecPlyrId=").append(idPlayerValidDeclared);
			}
			
			_cat.debug("temp haha : " + temp + ", drawString : " + _drawString);

			if (idPlayerKnocking != -1) {
				String str23 = "";
//				for (int k = 0; k < idPlayerKnockedUpon.length; k++){
					if (idPlayerKnockedUpon != -1){
						str23 += idPlayerKnockedUpon + "|";
					}
//				}
				
				if (!str23.isEmpty())
					str23 = str23.substring(0, str23.length() - 1);
				
				temp.append(",KnockedOnPlayer=").append(str23);
			}

			if (idPlayerKnocking != -1) {
				temp.append(",KnockingPlyrId=").append(idPlayerKnocking);
				if (_players[idPlayerKnocking] != null)
					temp.append(",KnockingPlyrName=").append(_players[idPlayerKnocking].name);
			}
			
			if (!_knockingCardStr.isEmpty())
				temp.append(",KnockingCardsString=").append(
						_knockingCardStr);
			else
				temp.append(",KnockingCardsString=");

			temp.append(",NextMovePos=").append(_nextMovePlayer);
			temp.append(",NextMoveId=").append(_nextMovesAllowed);
			temp.append(",LastMove=").append(_lastMove);
			temp.append(",LastMovePos=").append(_lastMovePos);
			temp.append(",LastMoveType=").append(_lastMoveString);

			temp.append(",DealingOrder=").append(_cardDealingOrder);
			
			_cat.debug("temp hoohoo : " + temp + " , winnerpos : " + _winnerPos + ", fixingin dealer : " + fixingDealerOngoing);

			// add the bit about checking cards of players
			if (fixingDealerOngoing) {
				String str = "";
				for (int i = 0; i < MAX_PLAYERS; i++) {
					if (_players[i] != null  && isActive(_players[i])) {
						Card tempCr = new Card(_players[i].fixPosCard);
						str += i + "`" + tempCr.toString() + "'";
					}
				}
				str = str.substring(0, str.length() - 1);
				temp.append(",FixDealerProcess=").append(1);
				temp.append(",FixDealerCards=").append(str);
			}

			if (_winnerPos != -1) {
				_cat.debug("temp this is for winner case.");
				temp.append(",Winner=").append(_winnerPos);
				
				if (_players[_winnerPos] != null){
					String str = "";
					if (_players[_winnerPos]._allotedCards != null && _players[_winnerPos]._allotedCards.length > 0){
						for (int k = 0; k < _players[_winnerPos]._allotedCards.length; k++)
							str += _players[_winnerPos]._allotedCards[k].toString()
									+ "'";
						str = str.substring(0, str.length() - 1);
					}
	
					temp.append(",WinnerCards=").append(str);
					if (!_players[_winnerPos].cardsStrFromClient.isEmpty())
						temp.append(",WinnerCardsString=").append(
								_players[_winnerPos].cardsStrFromClient);
	
					temp.append(",WinPoints=").append(
							_players[_winnerPos].getRUMMYWinAmt());
				}
				
				temp.append(",RoundResult=").append(_winnerString);
				temp.append(",RoundWinner=").append(_roundWinnerName);
				temp.append(",CurrentRoundResult=").append(_currRoundResStr);
				temp.append(",AmountsWon=").append(_amtWonString);
			} else {
				if (_drawString != null && !_drawString.isEmpty()) {
					_cat.debug("temp for case of draw. remove it .....");
					temp.append(",RoundResult=").append(_drawString);
					temp.append(",CurrentRoundResult=").append(_currRoundResStr);
					temp.append(",RoundWinner=").append(_roundWinnerName);
				}
			}
			
			_cat.debug("temp heeheh : " + temp);
			
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
						for (int k = 0; k < _players[i]._allotedCards.length; k++){
							str += _players[i]._allotedCards[k].toString() + "'";
						}
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
			temp.append("RummyOklaGin2Server=RummyOklaGin2Server");
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
			if (_players[i] != null) {
				StringBuffer tempPlayerData = new StringBuffer(temp);
				tempPlayerData.append(",PlayerPos=").append(i);
				sendMessage(tempPlayerData, _players[i]);
			}
			// }
		}

		public void sendErrorMessage(int prpos, int resCode) {
			StringBuffer temp = new StringBuffer();
			temp.append("RummyOklaGin2Server=RummyOklaGin2Server");
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
				temp.append(",MsgDropped=").append("OnlyDrawAllowed");//wrong move click
			} else if (resCode == 4) {
				temp.append(",MsgDropped=").append("WrongAction");//can't do it
			} else if (resCode == 5) {
				temp.append(",MsgDropped=").append("OnlyDeclareAllowed");//can't do it
			} else if (resCode == 11) {
				temp.append(",PenalCards=" + globalPenal + "'firstLife="
						+ globalFirstLife + "'secondLife=" + globalSecondLife);
			} else {
				temp.append(",MsgDropped=GetLost");
			}

			temp.append(",Dealer=").append(_dealer);
			temp.append(",RummyPlayer=").append(_rummyPlayer);
			temp.append(",GameOn=").append(_gameOngoing);

			if (_discardCard != null)
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
			buf.append("RummyOklaGin2Server=RummyOklaGin2Server,grid=")
					.append(-1)
					.append(",MsgDropped=InvalidTable,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}

		int pos = getPos(movedet);
		if (pos < 0 || pos > MAX_PLAYERS) {
			buf.append("RummyOklaGin2Server=RummyOklaGin2Server,grid=")
					.append(-1)
					.append(",MsgDropped=InvalidPos,player-details=" + p.name()
							+ "|" + p.netWorth());
			return buf;
		}

		_tables[tid]._players[pos].setReloadReq(true);
		buf.append("RummyOklaGin2Server=RummyOklaGin2Server,grid=").append(p.getGRID())
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

		StringBuffer buf = new StringBuffer();// for response back to player
		_cat.debug("RummyOklaGin2Server game - sit in req --" + movedet);

		if (movedet == null || movedet.equals("null")) {
			return null;
		}
		if (tid >= MAX_TABLES || tid < 0 || !_tables[tid].validTable) {
			buf.append("RummyOklaGin2Server=RummyOklaGin2Server,grid=")
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
			// just wants to observe, it is ok
			// put a conditon over here for the plaeyrs who want to come back
			// they had placed some bets and are still valid players on the
			// table

			if (!_tables[tid]._gameOngoing
					&& _tables[tid].getCurrentPlayersCount() == 0)
				_tables[tid]._nextMoveExpTime = System.currentTimeMillis();
			
			if (origPos != -1) {
				// found him, seated already.
				_cat.debug("seated already from rummysitin : "
						+ origPos);
			} else {

				if (p.getAmtAtTable() < 500 * _tables[tid].POINTSPERRUPEE) {
					buf.append("RummyOklaGin2Server=RummyOklaGin2Server,grid=")
							.append(-1)
							.append(",TID=")
							.append(tid)
							.append(",MsgDropped=PlayerBroke,player-details="
									+ p.name() + "|" + p.netWorth());
					return buf;
				}
				
				// create a new rummyprofile for this presence
				RummyProfile kp = new RummyProfile();
				kp.setName(p.name());
				kp.setGameStartWorth(p.getAmtAtTable());
				kp.setPresence(p);
				p.setKPIndex(_tables[tid].addObserver(kp));
				_cat.debug("ADDING OBSERVOER : " + kp.name);
				kp.setRUMMYStatus(0);
				kp.rummyLastMoveTime = System.currentTimeMillis();
				buf.append("RummyOklaGin2Server=RummyOklaGin2Server,grid=")
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
			buf.append("RummyOklaGin2Server=RummyOklaGin2Server,grid=")
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
			buf.append("RummyOklaGin2Server=RummyOklaGin2Server,grid=")
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
		_cat.debug("RummyOklaGin2Server game - move req --" + movedet);

		if (movedet == null || movedet.equals("null")) {
			buf.append("RummyOklaGin2Server=RummyOklaGin2Server,grid=")
					.append(-1)
					.append(",MsgDropped=MsgDropped,player-details=" + p.name()
							+ "|" + p.netWorth());
			return buf;
		}
		if (tid >= MAX_TABLES) {
			buf.append("RummyOklaGin2Server=RummyOklaGin2Server,grid=")
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

		// just mark the player as left. on completion of cycle, the player will
		// be removed
		kp.rummyLeftTableTime = System.currentTimeMillis();
		kp.setRUMMYMovesStr("&Leave");
		kp.setRUMMYMoveId(move_RUMMY_LEFT);
		
		kp.setRUMMYStatus(status_LEFT);
		
		_tables[tid].handleMoveNoAction(kp.pos());
		
		_cat.debug("take action plyr left : " + kp.pos()
				+ " from table : " + tid);
		kp.setRUMMYStatus(status_LEFT);
		kp.unsetRummyPlayer();
	}

}
