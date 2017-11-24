package com.hongkong.game.casino;

import java.io.IOException;
import java.security.Key;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Vector;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.apache.log4j.Logger;

import com.atlantis.util.Base64;
import com.atlantis.util.Rng;
import com.atlantis.util.Utils;
import com.hongkong.common.db.DBEAP;
import com.hongkong.common.db.DBException;
import com.hongkong.common.db.DBPoolWinner;
import com.hongkong.common.db.GameRunSession;
import com.hongkong.game.Game;
import com.hongkong.game.GameType;
import com.hongkong.game.Moves;
import com.hongkong.game.Player;
import com.hongkong.game.Player.Presence;
import com.hongkong.game.util.Card;
import com.hongkong.server.GamePlayer;
import com.hongkong.server.GameProcessor;

public class Rummy2Pool201Server extends CasinoGame {
	static Logger _cat = Logger.getLogger(Rummy2Pool201Server.class.getName());
	String _name;
	double minBet;
	double maxBet;
	double totalBet = 0, totalWin = 0, totalGames = 0;
	String moveDetails = "";
	
	int globalIndex = 0;

	public static final int MAXPLAYERS = 2;
	public static final int moveRUMMYINVALID = 0;
	public static final int moveRUMMYFOLD = 1;
	public static final int moveRUMMYNEWCARD = 2;
	public static final int moveRUMMYDISCARD = 4;
	public static final int moveRUMMYDECLARE = 8;
	public static final int moveRUMMYRUMMYCUT = 16;
	public static final int moveRUMMYLEFT = 32;
	public static final int moveRUMMYJOINED = 64;
	public static final int moveRUMMYDECLARELOSER = 128;
	public static final int moveRUMMYMANUALSPLIT = 256;
	public static final int move_RUMMY_CHAT = 512;
	
	public static final int move_RUMMY_SITOUT = 1024;
	public static final int move_RUMMY_SITIN = 2048;
	
	public int globalPenal = 0;
	public boolean globalFirstLife = false, globalSecondLife = false;

	public static final int statusNONE = 1;
	public static final int statusACTIVE = 2;
	public static final int statusSITTINGOUT = 64;
	public static final int statusFOLDED = 128;
	public static final int statusLEFT = 256;
	public static final int statusNEXTGAMEJOIN = 2048;
	public static final int statusREJOINED = 4096;
	public static final int statusLOST = 8192;

	static final int USEDSEENONCE = 1;
	static final int USEDSEENTWICE = 2;
	static final int USEDSEENTHRICE = 4;
	static final int USEDPURERUN = 8;
	static final int USEDIMPURERUN = 16;
	static final int USEDINSET = 32;
	static final int USEDASJOKER = 64;
	static final int USEDASTEMPJOKER = 128;

	public static final int MAXTABLES = 10;

	Rummy2PoolOneTable[] tables = null;

	boolean keepServicing = false;
	
	ArrayList<ClassRank> listWinners = null;
	
	Calendar createdTournyTime;

	private LinkedList[] msgQ = new LinkedList[MAXTABLES];

	public void add(int index, Presence cmdStr) {
		(msgQ[index]).add(cmdStr);
	}

	public Presence fetch(int index) {
		return (Presence) (msgQ[index].removeFirst());
	}

	public Rummy2Pool201Server(String name, double minB, double maxB, GameType type,
			int gid) {
		_gid = gid;
		_name = name;
		minBet = minB;
		maxBet = maxB;
		_type = type;
		//_cat.debug(this);
		keepServicing = true;
		// one linkedlist per thread
		for (int i = 0; i < MAXTABLES; i++) {
			msgQ[i] = new LinkedList();
		}
		tables = new Rummy2PoolOneTable[MAXTABLES];
		for (int i = 0; i < MAXTABLES; i++) {
			tables[i] = new Rummy2PoolOneTable();
			tables[i].validTable = true;
			tables[i].POINTSPERRUPEE = (i + 1) * 1;
			

			Thread t = new Thread(tables[i]);
			t.setName("RummyPool2-Table-" + i);
			t.setPriority(Thread.NORM_PRIORITY);
			tables[i].setIndex(i);
			t.start();

			_cat.debug("starting 2 pool 201 thread : " + i);
		}
	}

	public StringBuffer gameDetail() {
		StringBuffer sb;
		sb = new StringBuffer("Rummy2Pool201Server=Rummy2Pool201Server").append(",min-bet=")
				.append(minBet).append(",max-bet=").append(maxBet)
				.append(",RummyTables=");
		// now add details of all tables
		for (int i = 0; i < MAXTABLES; i++) {
			if (tables[i].validTable) {
				double dbl = tables[i].POINTSPERRUPEE * tables[i].buyIn;
				// int intdbl = ((int) dbl / 100);
				sb.append(dbl);
				sb.append("'");
				sb.append(tables[i].tid).append(
						"'" + tables[i].getMaxPlayers());
				sb.append("'" + tables[i].getCurrentPlayersCount());
				String details = tables[i].getCurrentPlayersDetail();
				sb.append("'" + details);

				if (i != MAXTABLES - 1)
					sb.append(":");
			}
		}
		return sb;
	}

	class ClassRank {
		String name;
		double chips;
		int rank;
		double winAmt;
		
		ClassRank(String name){
			this.name = name;
		}
		
		public String getRummyProfile(){
			return name;
		}
		
		public void setChips(double val){
			chips = val;
		}
		
		public void setRank(int val){
			rank = val;
		}
		
		public int getRank(){
			return rank;
		}
		
		public void setWinAmt(double val){
			winAmt = val;
		}
		
		public double getWinAmt(){
			return winAmt;
		}
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
			
			if (val != -1){
				totalScore += val;
				_cat.debug("value of totalscore is " + totalScore);
			}
		}

		double rummyWinAmt;

		public double getRUMMYWinAmt() {
			return rummyWinAmt;
		}

		public void setRUMMYWinAmt(double winamt) {
			rummyWinAmt = winamt;
		}

		int pos = -1;

		public int pos() { // _cat.debug("HI");
			return pos;
		}

		public void setPos(int pos) { // _cat.debug("HI");
			this.pos = pos;
		}

		public int tid;

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

		int state = -1;
		final static int RUMMYPLAYER = 8;

		public boolean isRummyPlayer() { // _cat.debug("HI");
			return (state & RUMMYPLAYER) > 0;
		}

		public void setRummyPlayer() { // _cat.debug("HI");
			state |= RUMMYPLAYER;
		}

		public void unsetRummyPlayer() { // _cat.debug("HI");
			state &= ~RUMMYPLAYER;
		}

		public Card[] allotedCards = null;
		public boolean firstMoveDone = false;

		public String cardsStrFromClient = "";

		public boolean markCheckingCards = false;

		public int fixPosCard = -1;
		
		public int totalScore = 0;
	}

	class Rummy2PoolOneTable implements Runnable {
		volatile boolean gameOngoing = false;
		double _rake;
		static final double RAKEPERCENTAGE = 0.05;
		double POINTSPERRUPEE = 1.0;
		volatile RummyProfile[] players = new RummyProfile[MAXPLAYERS];
		// all the players who paid ante or who opted out of ante only - for
		// entries in grs table
		int nextMovesAllowed;// to remember the moves that are allowed
		volatile int nextMovePlayer = -1;
		volatile int dealer;// to mark the very first player
		volatile int rummyPlayer;// first one to make a move.
		int[] winnerPos = null;
		int winnerCount = 0;//there can be up to 2 winners of the game. we are talking game now. not of rounds.
		
		volatile boolean poolStarted = false;
		int roundWinner = -1; //this has to be only one.
		
		int roundNum = 0;

		boolean fixingDealerOngoing = false;
		int countHandsAfterDealerFix = 0;

		String winnerString = "";
		
		String amtWonString = "";

		String newCardAdded = "";

		String cardDealingOrder = "";
		
		String chatMessage = "";
		int chatOn = 0;

		// the card for which the players wage war
		Card rummyCardJoker, discardCard, prevDiscardCard;

//		String finalResultRuns = "", finalResultRuns3 = "",
//				finalResultRuns1 = "", finalResultRuns2 = "";

		int tanalaCount = 0;
		int idPlayerChecked = -1;

		int idPlayerValidDeclared = -1;

		int countPlayerResponseDeclareLoser = 0;

		boolean validTable = false;

		BitSet deck = new BitSet(); // this is for fresh pile
		Card[] discardDeck = new Card[65]; // when ever a card is discarded, we
											// add it here. when one is removed,
											// prev card comes to fore.
		// when the fresh pile runs out, we unset the bits in desk that are set
		// in discardDeck and empty out discardDeck
		int indexDiscardDeck;

		int totalJokersAlreadyDealt = 0;
		boolean printedJokerDealt = false;

		int NUMDECKS = 3;
		int MAXCARDS = 52 * NUMDECKS - 1;

		int lastMove = -1;
		int lastMovePos = -1;
		String lastMoveString = "";

		Vector observers = new Vector();

		long rummygrid;
		Calendar gameStartTime;

		long nextMoveExpTime;
		
		int counterGameNotStarted = 0;

		int tid = -1;

		volatile long lastRespTime = 0;
		
		String currRoundResStr = "", combinedRoundRes = "";
		
		int countPotContenders, numRejoins;
		
		String[] names = new String[MAXPLAYERS];
		
		double pot;
		
		double buyIn = 50;//fixed for now, should come from db or something...
		
		boolean manualSplitOn, autoSplitOn;
		
		int splitOptedFor = 0;
		
		boolean manualSplitProcessOn = false;
		int manualSplitResponders = 0;
		
		int holdNextMovePlayer = -1;
		int holdNextMoves = -1;
		
		//for sitting out players
		ArrayList<Integer> sitoutPlayers = new ArrayList<Integer>();
		ArrayList<Integer> sitinPlayers = new ArrayList<Integer>();
		
		public Rummy2PoolOneTable() {
			for (int i = 0; i < MAXPLAYERS; i++) {
				players[i] = null;
			}
			nextMoveExpTime = System.currentTimeMillis();
		}

		public void setIndex(int index) {
			tid = index;
		}

		public RummyProfile findProfile(Presence p) {
			for (int k = 0; k < MAXPLAYERS; k++) {
				if (players[k] != null
						&& players[k].getName().compareToIgnoreCase(p.name()) == 0) {
					return players[k];
				}
			}
			return null;
		}

		public RummyProfile findProfile(String name) {
			for (int k = 0; k < MAXPLAYERS; k++) {
				if (players[k] != null
						&& players[k].getName().compareToIgnoreCase(name) == 0) {
					return players[k];
				}
			}
			return null;
		}

		public int addObserver(RummyProfile p) {
			// first check if he is already in this list, if so don't do
			// anything
			int i = 0;
			for (i = 0; i < observers.size(); i++) {
				RummyProfile pro = (RummyProfile) observers.get(i);
				if (p.getName().compareToIgnoreCase(pro.getName()) == 0)
					return i;
			}
			observers.add(p);
			_cat.debug("adding observor : " + p.getName()
					+ " on table : " + tid + " on pos : " + i);
			return observers.size();
		}

		public void removeObserver(RummyProfile p) {
			observers.remove(p);
		}

		public int findObsPos(Presence p) {
			for (int i = 0; i < observers.size(); i++) {
				RummyProfile pro = (RummyProfile) observers.get(i);
				_cat.debug("from list : " + pro.getName());
				if (p.name().compareToIgnoreCase(pro.getName()) == 0)
					return i;
			}
			return -1;
		}

		private RummyProfile findObservor(int pos) {
			if (pos != -1)
				return (RummyProfile) observers.get(pos);
			else
				return null;
		}
		
		private RummyProfile findObservor(String name) {
			if (!name.isEmpty()) {
				for (int i = 0; i < observers.size(); i++){
					RummyProfile rp = (RummyProfile) observers.get(i);
					if (rp.name.compareToIgnoreCase(name) == 0)
						return rp;
				}
			}
			return null;
		}

		public void removeFromTable(int index) {
			// for (int k = 0; k < MAXPLAYERS; k++) {
			// if (players[k] != null
			// && players[k].getName().compareToIgnoreCase(
			// p.getName()) == 0) {
			// // found the offending player
			// players[k].rummyLeftTableTime = -1;
			// players[k] = null;
			// _cat.debug("kicked out .... from table : " + tid);
			// break;
			// }
			// }

			if (players[index] != null) {
				players[index].rummyLeftTableTime = System.currentTimeMillis();
				// players[index] = null; //can't make it null
				players[index].setRUMMYStatus(statusLEFT);
				players[index].setRUMMYMoveId(moveRUMMYLEFT);
			}
		}

		public void resetTable(boolean tosendmsg) {
			//find a friend feature - clear out here
			for (int m = 0; m < MAXPLAYERS; m++) {
				if (players[m] != null) {
					players[m].getPresence().player().setActiveGame(-1);
				}
			}
			
			if (!gameOngoing){
				handleSitoutSitin();
			}
			
			// first cull the players - this will leave some open seats
			// _cat.debug("from reset table");
			for (int m = 0; m < MAXPLAYERS; m++) {
				if (players[m] != null) {
					if (isRemoved(players[m])
							|| isSittingOut(players[m])
							|| isLeft(players[m])
							)
					// ||!players[m].getRUMMYBetApplied() //this condition is
					// to throw out the players who just sit on table
					{
						if (!gameOngoing && !isSittingOut(players[m])) {
							sendMessageKickedOut(m, 0);
							removeFromTable(m);
						}
						
						players[m].clearRUMMYMovesStr();
						players[m].setReloadReq(false);
						players[m].allotedCards = null;
						players[m].firstMoveDone = false;
						players[m].cardsStrFromClient = "";
						
						
					} else {
						
						if (isToJoinNextGame(players[m]) && gameOngoing){
							continue;//game not over yet. can't join now.
						}
						
						players[m].allotedCards = null;
						players[m].firstMoveDone = false;
						
						if (isLost(players[m]) && gameOngoing){
							continue;
						}
						
						//check if this player has money for next game
						if (players[m].getPresence().getAmtAtTable() < buyIn * POINTSPERRUPEE){
							players[m].setRUMMYStatus(statusLEFT);
							_cat.debug("can't afford to rejoin....");
							sendErrorMessage(m, 6);
							removeFromTable(m);
							continue;
						}
						
//						if (isRejoined(players[m])){
//							players[m].totalScore = getHighestScore() + 1;
//							_cat.debug("rejoining player " + m + " gets score updated here...");
//						}
						
						players[m].clearRUMMYMovesStr();
						players[m].setReloadReq(false);
						players[m].rummyLastMoveTime = System
								.currentTimeMillis();
						players[m].cardsStrFromClient = "";
						
						if (gameOngoing && players[m].totalScore >= 201){
							players[m].setRUMMYStatus(statusLOST);
							
							_cat.debug("marking " + m + " as LOST from resetTable and haha its totalscore is " + players[m].totalScore);
						}
						else {
							players[m].setRUMMYStatus(statusACTIVE);
						}
					}
				}
			}
			winnerPos = null;
			winnerCount = 0;
			
			//find a friend feature
			for (int m = 0; m < MAXPLAYERS; m++) {
				if (players[m] != null && !isRemoved(players[m]) && !isLeft(players[m])) {
					players[m].getPresence().player().setActiveGame(4);
					players[m].getPresence().player().setActiveTable(tid);
				}
			}
			
			//for all players marked left, they get a proper message
			for (int i = 0; i < MAXPLAYERS; i++){
				if (players[i] != null && isLeft(players[i])){
					sendMessageKickedOut(i, 0);
					players[i] = null;
					tosendmsg = true;
				}
			}
			
			if (tosendmsg)
				broadcastMessage(-1);

		}

		public int findPos(Presence p) {
			for (int k = 0; k < MAXPLAYERS; k++) {
				if (players[k] != null
						&& players[k].getName().compareToIgnoreCase(p.name()) == 0) {
					return k;
				}
			}
			return -1;
		}

		private void initGame() {
			_cat.debug("have to come here to clear out all stuff...."
					+ " on table : " + tid);
//			finalResultRuns = "";
//			finalResultRuns3 = "";
//			finalResultRuns1 = "";
//			finalResultRuns2 = "";

			idPlayerChecked = -1;

			idPlayerValidDeclared = -1;

			countPlayerResponseDeclareLoser = 0;

			deck.clear();
			discardDeck = new Card[65];
			indexDiscardDeck = 0;

			totalJokersAlreadyDealt = 0;
			
			printedJokerDealt = false;

			// find the rummy card and the last card that will never come
			int randCard = drawCard();
			// if rummy card comes to be joker, then make Ace of Clubs as joker
			if (randCard >= 161) {
				deck.clear(randCard);
				randCard = 12;
				deck.set(randCard);
			}
			rummyCardJoker = new Card(randCard);
			rummyCardJoker.setIsOpened(true);

			randCard = drawCard();
			discardCard = new Card(randCard);
			discardCard.setIsOpened(true);

			discardDeck[indexDiscardDeck++] = discardCard;

			prevDiscardCard = new Card(randCard);

//			indicesCardsInRun = new String[13]; // it will have entries stored
//												// as count'7`8`9|2`3`4 - will
//												// be helpful in computing
//												// penalty
//			for (int i = 0; i < 13; i++)
//				indicesCardsInRun[i] = "";
			removeObservorsTurnedPlayers();

			newCardAdded = "";
//			globalJokerTracker = "";
//			bitsetJoker = new BitSet();
//			bitsetPlyrCards = new BitSet();
//			removedCardsDupli = new int[6];// expecting at most 6 pairs
//			indexDupli = 0;
//			playerStatus = new int[52];
//			for (int i = 0; i < 6; i++) {
//				removedCardsDupli[i] = -1;
//			}
//			for (int i = 0; i < 52; i++) {
//				playerStatus[i] = 0;
//			}

			lastMovePos = -1;
			lastMove = 0;
			lastMoveString = "";
		}
		
		private void removeObservorsTurnedPlayers(){
			for (int i = 0; i < players.length; i++){
				if (players[i] != null){
					//check if this player is in observors list. if so, remove it
					for (int k = 0; k < observers.size(); k++){
						RummyProfile rp = (RummyProfile) observers.get(k);
						if (rp.name.compareToIgnoreCase(players[i].getName()) == 0){
							//found it. remove it
							observers.remove(k);
							break;
						}
					}
				}
			}
		}

		public int getIndex() {
			return tid;
		}

		public int getMaxPlayers() {
			return MAXPLAYERS;
		}

		public int getCurrentPlayersCount() {
			int actualCount = 0;
			for (int i = 0; i < MAXPLAYERS; i++) {
				if (players[i] != null && players[i].isRummyPlayer())
					actualCount++;
			}
			return actualCount;
		}

		public String getCurrentPlayersDetail() {
			StringBuffer det = new StringBuffer();
			for (int i = 0; i < MAXPLAYERS; i++) {
				if (players[i] != null && players[i].isRummyPlayer()) {
					det.append(players[i].getName() + "`"
							+ players[i].getRUMMYStatus());
					det.append("`").append(players[i].pos());
					det.append("|");
				}
			}
			if (det.length() > 1)
				det.deleteCharAt(det.length() - 1);
			return det.toString();
		}

		public void addPresence(RummyProfile p) {
			players[p.pos()] = p;
		}

		public int findFirstAvailPos() {
			int i = 0;
			for (i = 0; i < MAXPLAYERS; i++) {
				// dealer and rummyplayer even if left have to be maintained -
				// no one can sit in their place
				if (players[i] == null)
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
				System.out
						.println("can't find the player in observors list or old players : "
								+ obspos);
				sendErrorMessage(p.pos, 3);
				return;
			}

			// if (gameOngoing) {
			// //game in progress, no one can join now, wait for game over
			// _cat.debug("no one joins during game!!!");
			// return;
			// }

			_cat.debug("player found : " + p.getName() + " , rejoin : "
					+ rejoinReq);
			int pos = -1;
			if (!rejoinReq) {
				int countPlayersInit = getCountActivePlayers();
				_cat.debug("found " + countPlayersInit
						+ " players on table ");
				if (countPlayersInit >= 2 || fixingDealerOngoing
						|| gameOngoing) {

					pos = adjustNextToDealer(p);
					if (pos == -1) {
						System.out
								.println("despite attempts no avail pos at table :"
										+ tid + " for player : " + p.getName());
						sendErrorMessage(p.pos, 3);
						return;
					}
					
					chatOn = 0;
					chatMessage = players[pos].name + " at pos : " + (pos + 1) + " joined the table";
					
					players[pos].getPresence().player().setActiveGame(4);
					players[pos].getPresence().player().setActiveTable(tid);
					
					players[pos].setTimeBank(30000);

					_cat.debug("new nextmovepos : " + nextMovePlayer
							+ " , new rummy player tag : " + rummyPlayer);
					if (!gameOngoing) {
						//player might try to join at the time when server is still settling the previous round
						if (!isGameAlreadyStarted())
							p.setRUMMYStatus(statusACTIVE);
						else
							p.setRUMMYStatus(statusNEXTGAMEJOIN);
					}
					else {
						p.setRUMMYStatus(statusNEXTGAMEJOIN);
					}
				} else {
					// less than 2 players - call resettable once
					resetTable(false);
					countPlayersInit = getCountTotalPlayers();

					if (countPlayersInit == 0) {
						pos = 0;// very first player
						players[0] = p;
						clearImpVars(); //clear these variables here. let us see if this solves our problem.
						nextMoveExpTime = System.currentTimeMillis();
					} else if (countPlayersInit == 1) {
						//check if the already seated plaeyr is on pos 1.
						//if so, give pos 0 to the incoming player
						//after all, there is only 1 player on table. 0 is empty
						if (players[1] != null)
							pos = 0;
						else
							pos = 1;
						players[pos] = p;
						//now there are 2 players. game can start now.
						nextMoveExpTime = System.currentTimeMillis();
					}
					chatOn = 0;
					chatMessage = p.name + " at pos : " + (pos + 1) + " joined the table";
					
					players[pos].getPresence().player().setActiveGame(4);
					players[pos].getPresence().player().setActiveTable(tid);
					
					players[pos].setTimeBank(30000);
					
					p.setRUMMYStatus(statusACTIVE);// the first 2 players have
													// to be marked active
				}
			} else {
				//a player can't join back immediately on the same table he left. it could be a ploy to cheat the house
				//TBD - check if currently playing players list has this name. if so, then kick him out
				//else allow him
				
				pos = p.pos;
				//bug : if rejoin comes again, do not send that one player has more than 175
				if (isToJoinNextGame(players[pos]) || isRejoined(players[pos])){
					_cat.debug("trying to rejoin again. drop the message...");
					sendErrorMessage(p.pos, 3);
					return;
				}
				
				//check if there are only 2 players left on table and this player having lost
				//is again trying to worm his way back to the table
				if (getCountActivePlayers() < 2){
					_cat.debug("trying to rejoin when you have lost is bad...");
					sendErrorMessage(p.pos, 3);
					return;
				}
				
				//TBD - check condition of points being less than 174 here.....
				for (int m = 0; m < players.length; m++){
					if (players[m] != null && !isLost(players[m]) && !isLeft(players[m]) && !isRemoved(players[m])
							&& !isToJoinNextGame(players[m]) && !isRejoined(players[m])
							){
						if (players[m].totalScore > 174){
							_cat.debug("highest score of some players is 174 or more. can't join now");
							p.setRUMMYStatus(statusLOST);
							sendErrorMessage(p.pos, 5);
							return;
						}
					}
				}
				
				chatOn = 0;
				chatMessage = players[pos].name + " at pos : " + (pos + 1) + " rejoined.";
				
				_cat.debug("returning player joining at " + pos + " has score : " + p.totalScore + " and round started : " + poolStarted);
				
				//deduct money. set totalscore to 1 more than highest player. 
				if (p.totalScore >= 201)
					p.setRUMMYStatus(statusLOST);
				
				if (isLost(p)) {
					
					//first check if player has enough money to make payment
					if (p.getPresence().getAmtAtTable() < buyIn * POINTSPERRUPEE){
						p.setRUMMYStatus(statusLEFT);
						_cat.debug("can't afford to rejoin....");
						sendErrorMessage(p.pos, 6);
						return;
					}
					
					p.getPresence().currentRoundBet(buyIn * POINTSPERRUPEE);
					double delta = buyIn * POINTSPERRUPEE * RAKEPERCENTAGE;
					_rake += delta;
					pot += (buyIn * POINTSPERRUPEE - delta);
					_cat.debug("pot updated. bet amt deducted from player...");
					
					p.clearRUMMYMovesStr();
					p.setReloadReq(false);
					p.allotedCards = null;
					p.firstMoveDone = false;
					p.cardsStrFromClient = "";
					
					p.rummyLastMoveTime = System.currentTimeMillis();
					
					numRejoins++;
					
					//no manual split option for now 'coz someone has rejoined...
					manualSplitOn = false;
					
					if (poolStarted){
						p.setRUMMYStatus(statusREJOINED);
						p.setRUMMYPoints(-1);
					}
					else {
						//get the rejoined players' score here itself
						p.totalScore = getHighestScore() + 1;
						p.setRUMMYStatus(statusACTIVE);
					}
				}
				else {
					if (poolStarted) {
						p.setRUMMYStatus(statusREJOINED);
					}
					else {
						p.setRUMMYStatus(statusACTIVE);
					}
				}
				
			}

			removeObserver(p);

			// now make him seat on the table on that position
//			p.setPresence(pold);
			_cat.debug("pos of palyer is : " + pos);
			p.setPos(pos);
			p.setRummyPlayer();
			p.getPresence().setRUMMYTID(tid);
			p.getPresence().lastMove(Moves.RUMMY_SITIN);
			p.setRUMMYMoveId(0);

			addPresence(p);
			System.out
					.println("Rummy2Pool201Server game - sit in req buf -- on table : "
							+ tid);
			// send message to all players about the new player
			lastMove = moveRUMMYJOINED;
			lastMovePos = pos;
			lastMoveString = "Joined";

			p.rummyLeftTableTime = -1;
			p.rummyLastMoveTime = System.currentTimeMillis();
			
			int countplayers = getCntStatusActivePlayers();

			if (!gameOngoing) {
				if (countplayers == 2){
					fixDealerFirst();
				}
				else {
					winnerPos = null;
					winnerCount = 0;
					broadcastMessage(-1);
				}
			}
		}
		
		public int getHighestScore(){
			int highestScore = 0;
			for (int k = 0; k < players.length; k++){
				if (players[k] != null && !isToJoinNextGame(players[k]) && !isLost(players[k])
						&& !isRejoined(players[k]) && players[k].totalScore < 201
						){
					if (highestScore < players[k].totalScore)
						highestScore = players[k].totalScore;
				}
			}
			return highestScore;
		}

		public void removePresence(RummyProfile p) {
			players[p.pos()] = null;
		}

		public RummyProfile getPresence(int index) {
			return players[index];
		}

		public boolean isActive(RummyProfile p) {
			if ((p.getRUMMYStatus() & statusACTIVE) > 0) {
				return true;
			}
			return false;
		}

		public boolean isRemoved(RummyProfile p) {
			return isLeft(p);
		}
		
		public boolean isToJoinNextGame(RummyProfile p) {
			if ((p.getRUMMYStatus() & statusNEXTGAMEJOIN) > 0){
				return true;
			}
			return false;
		}
		
		public boolean isRejoined(RummyProfile p) {
			if ((p.getRUMMYStatus() & statusREJOINED) > 0){
				return true;
			}
			return false;
		}
		
		public boolean isLost(RummyProfile p) {
			if ((p.getRUMMYStatus() & statusLOST) > 0){
				return true;
			}
			return false;
		}

		public boolean isLeft(RummyProfile p) {
			if ((p.getRUMMYStatus() & statusLEFT) > 0) {
				return true;
			}
			return false;
		}

		public boolean isFolded(RummyProfile p) {
			if ((p.getRUMMYStatus() & statusFOLDED) > 0) {
				return true;
			}
			return false;
		}

		public boolean isSittingOut(RummyProfile p) {
			if ((p.getRUMMYStatus() & statusSITTINGOUT) > 0) {
				return true;
			}
			return false;
		}

		public boolean isGameAlreadyStarted(){
			_cat.debug("isgamealready started method");
			for (int i = 0; i < MAXPLAYERS; i++) {
				if (players[i] != null && !isLeft(players[i])
						&& players[i].totalScore > 0)
					return true;
			}

			return false;
		}

		public int getCountActivePlayers() {
//			_cat.debug("getcntactpl : ");
			int cnt = 0;
			for (int i = 0; i < MAXPLAYERS; i++) {
				if (players[i] != null 
						//&& !isLeft(players[i])
						&&
						// !isFolded(players[i]) &&
						!isToJoinNextGame(players[i]) &&
						!isLost(players[i]) &&
						!isLeft(players[i]) &&
						!isRemoved(players[i]) &&
						!isRejoined(players[i]) &&
						!isSittingOut(players[i]) //removed !isRemoved(players[i]) &&  condition.
						)
					cnt++;
			}

			return cnt;
		}
		
		public int getCntStatusEligiblePlayers() {
//			_cat.debug("getcntstatusactpl : ");
			int cnt = 0;
			for (int i = 0; i < MAXPLAYERS; i++) {
				if (players[i] != null && (isActive(players[i]) || isFolded(players[i]) || isRejoined(players[i])
						)
						)
					cnt++;
			}

			return cnt;
		}

		public int getCntStatusActivePlayers() {
//			_cat.debug("getcntstatusactpl : ");
			int cnt = 0;
			for (int i = 0; i < MAXPLAYERS; i++) {
				if (players[i] != null && isActive(players[i]) && (players[i].allotedCards == null || players[i].allotedCards.length > 0))
					cnt++;
			}

			return cnt;
		}

		public int getCountTotalPlayers() {
			int cnt = 0;
			for (int i = 0; i < MAXPLAYERS; i++) {
				if (players[i] != null && !isLeft(players[i])
						 && !isRemoved(players[i]))
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
			while ((players[pos1].getRUMMYStatus() & statusFOLDED) > 0
					|| (players[pos1].getRUMMYStatus() & statusREJOINED) > 0
					|| (players[pos1].getRUMMYStatus() & statusSITTINGOUT) > 0
					|| (players[pos1].getRUMMYStatus() & statusNEXTGAMEJOIN) > 0
					|| (players[pos1].getRUMMYStatus() & statusLOST) > 0
					|| (players[pos1].getRUMMYStatus() & statusLEFT) > 0) {
				_cat.debug("find next one : " + pos1 + " , stat: "
						+ players[pos1].getRUMMYStatus() + " on table : "
						+ tid);

				pos1 = decrPos(pos1);
				countCalls++;
				if (countCalls >= MAXPLAYERS || pos1 == -1 || pos1 == pos) {
					// already this method has been called max times, nothing
					// comes out of it
					return -1;
				}
			}
			_cat.debug("next active pos : " + pos1 + " on table : "
					+ tid);
			return pos1;
		}

		private int decrPos(int pos) {
			int origPos = pos;
			// get the next position
			if (pos <= 0) {
				pos = MAXPLAYERS - 1;
			} else
				pos--;

			while (players[pos] == null) {
				pos--;
				if (pos < 0)
					pos = MAXPLAYERS - 1;

				if (pos == origPos)
					return -1;
			}
			_cat.debug("decrpos player pos : " + pos + " on table : "
					+ tid + ", from old pos : " + origPos);
			return pos;
		}

		public int getOnlyActivePos() {
			int pos1 = 0; // start from 0th index
			if (players[pos1] == null) {
				pos1 = incrPos(pos1);
				if (pos1 == -1) {
					return -1;
				}
			}

			int countCalls = 0;
			while ((players[pos1].getRUMMYStatus() & statusACTIVE) <= 0) {
				_cat.debug("find next one : " + pos1 + " , stat: "
						+ players[pos1].getRUMMYStatus() + " on table : "
						+ tid);

				pos1 = incrPos(pos1);
				countCalls++;
				if (countCalls >= MAXPLAYERS || pos1 == -1) {
					// already this method has been called max times, nothing
					// comes out of it
					return -1;
				}
			}
			_cat.debug("only active pos : " + pos1 + " on table : "
					+ tid);
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
			while ((players[pos1].getRUMMYStatus() & statusFOLDED) > 0
					|| (players[pos1].getRUMMYStatus() & statusREJOINED) > 0
					|| (players[pos1].getRUMMYStatus() & statusNEXTGAMEJOIN) > 0
					|| (players[pos1].getRUMMYStatus() & statusSITTINGOUT) > 0
					|| (players[pos1].getRUMMYStatus() & statusLEFT) > 0) {
				_cat.debug("find next one : " + pos1 + " , stat: "
						+ players[pos1].getRUMMYStatus() + " on table : "
						+ tid);

				pos1 = incrPos(pos1);
				countCalls++;
				if (countCalls >= MAXPLAYERS || pos1 == -1 || pos1 == pos) {
					// already this method has been called max times, nothing
					// comes out of it
					return -1;
				}
			}
			_cat.debug("next active pos : " + pos1 + " on table : "
					+ tid);
			return pos1;
		}

		private int incrPos(int pos) {
			int origPos = pos;
			// get the next position
			if (pos == MAXPLAYERS - 1) {
				pos = 0;
			} else
				pos++;

			while (players[pos] == null) {
				pos++;
				if (pos == MAXPLAYERS)
					pos = 0;
				if (pos == origPos)
					return -1;
			}
			_cat.debug("incrpos player pos : " + pos + " on table : "
					+ tid + ", from old pos : " + origPos);
			return pos;
		}

		private void handleMoveNoAction(int pos) {

			// always find the new dealer if player is leaving a table
			lastMovePos = pos;
			lastMoveString = "Left";
			lastMove = moveRUMMYLEFT;

			if (nextMovePlayer != 111) {

				if (pos == nextMovePlayer) {
					// same action as that of player leaving table
					handleTimedOut();
					return;
				}
				
				//bug : if a player simply joins table and leave without becoming part of pool,
				//his penalty can't be added.
				if (players[pos].allotedCards == null){
					_cat.debug("player leaving who is not part of pool");
					players[pos] = null;
					return;
				}
				
				chatOn = 0;
				chatMessage = players[pos].name + " at pos : " + (pos + 1) + " left.";

				// not the player making move, but still mark it as folded, set
				// penalty
				if (players[pos].firstMoveDone)
					players[pos].setRUMMYPoints(50);
				else {
					players[pos].setRUMMYPoints(25);
					// player didn't even play one card, so put his cards in
					// fresh
					// pile
					addCardsToFreshPile(players[pos].allotedCards, 13);
				}

				players[pos].allotedCards = null;
				
				// if there are only 2 players and one of them di the hooky then
				// the
				// other player has to win
				// now if the leaving player is nextmoveose it is handled above
				// but
				// the opposite might be true too :-( - that is winking, not sad
				if (!checkGameOver()) {
//					removePlayersDisconnected();
					broadcastMessage(-1);
				}
			}
		}

		private void handleTimedOut() {
			if (players[nextMovePlayer].firstMoveDone)
				players[nextMovePlayer].setRUMMYPoints(50);
			else {
				players[nextMovePlayer].setRUMMYPoints(25);
				// player didn't even play one card, so put his cards in fresh
				// pile
				addCardsToFreshPile(players[nextMovePlayer].allotedCards, 13);
			}

			lastMovePos = nextMovePlayer;
			if (!lastMoveString.contains("Left"))
				lastMoveString = "TimedOut";
			
			if (players[nextMovePlayer].isEligibleTimeBank()) {
				players[nextMovePlayer].setRUMMYMovesStr("&Folded");
				players[nextMovePlayer].setRUMMYStatus(statusFOLDED);
			}
			else {
				//mark the player as left for he has now exhausted time bank
				players[nextMovePlayer].setRUMMYMovesStr("&TimedOut");
				players[nextMovePlayer].setRUMMYStatus(statusLEFT);
				players[nextMovePlayer].setTimeBank(-1);
				players[nextMovePlayer].setUsingTimeBank(false);
			}
			
			players[nextMovePlayer].allotedCards = null;
			
			_cat.debug("player timed out " + nextMovePlayer);
			
			nextMoveExpTime = System.currentTimeMillis();
			
			chatOn = 0;
			chatMessage = players[nextMovePlayer].name + " at pos : " + (nextMovePlayer + 1) + " timed out";

			if (!checkGameOver()) {
				// game not over
//				removePlayersDisconnected();
				nextMovePlayer = getRightOfPlayer(nextMovePlayer);
				nextMovesAllowed = moveRUMMYDECLARE | moveRUMMYNEWCARD
						| moveRUMMYFOLD;
				if (manualSplitOn)
					nextMovesAllowed |= moveRUMMYMANUALSPLIT;
				broadcastMessage(-1);
			}
		}

		// posWinner tells us which one won out or in - many players might have
		// bet on out/in
		public void declareRoundOver(int winner) {
			nextMoveExpTime = System.currentTimeMillis();
			
			poolStarted = false;
//			gameOngoing = false;
			
			roundWinner = winner;
			
			int numActivePlayers = 0;
			
			StringBuffer sb = new StringBuffer();
			for (int m = 0; m < MAXPLAYERS; m++) {
				if (players[m] != null
						&& !isToJoinNextGame(players[m])
						&& !isSittingOut(players[m])
						&& !isLost(players[m])
//						&& !isRejoined(players[m])
						) {
					// compute the points of players still playing
					_cat.debug("pos : " + m + " come to give string and has status : " + players[m].getRUMMYStatus());
					int pts = 0;
					if (roundWinner != m){
						if (isFolded(players[m]) || isLeft(players[m]) || isRejoined(players[m])
								|| isLost(players[m]) || isRemoved(players[m]) || isSittingOut(players[m])
								) {
							pts = players[m].getRUMMYPoints();
							if (pts == -1)
								continue;
							
							players[m].setRUMMYPoints(-1);
						}
						else {
							idPlayerChecked = m;
							int[] retVal = checkValidCards2(
									players[m].allotedCards,
									players[m].cardsStrFromClient);
							pts = retVal[0];
							players[m].setRUMMYPoints(pts);
						}
	
						players[m].setRUMMYMovesStr("&Penalty^" + pts);
					}
					
					//add winner declaration
					if (roundWinner == m){
						players[m].setRUMMYMovesStr("&Winner^" + players[m].getName());
						players[m].setRUMMYMovesStr("&WinPoints^0");
					}
					
					sb.append(m + "`" + players[m].name + "`" + pts + "'");
					numActivePlayers++;
				}
			}
			
			if (numActivePlayers > 1) {
				sb.deleteCharAt(sb.length() - 1);
				
				if (!combinedRoundRes.isEmpty())
					combinedRoundRes += "|" + sb.toString();
				else
					combinedRoundRes = sb.toString();
				
				currRoundResStr = sb.toString();
			}

			// hack - comment later
			_cat.debug("winners win amt computd");
			_cat.debug("current round res : " + currRoundResStr);

			int status = determineGameOver();
			_cat.debug("status from detrminegameover : " + status);
			
			if (status > 0){
				//game is over. restart timer here so that client's timer is synchronized
				nextMoveExpTime = System.currentTimeMillis();
				
				if (status == 1){
					
					//lone player standing. he is the winner....
					winnerPos = new int[1];
					winnerPos[0] = getOnlyActivePos();
					winnerCount = 1;
					players[winnerPos[0]].getPresence().addToWin(pot);
					players[winnerPos[0]].setRUMMYWinAmt(pot);
					gameOngoing = false;
					
					ClassRank crn = new ClassRank(players[winnerPos[0]].getName());
					crn.setRank(1);
					crn.setChips(players[winnerPos[0]].getPresence().getTotalWorth());
					crn.setWinAmt(pot);
					listWinners.add(crn);
				}
				else if (status >= 2){
					gameOngoing = false;
					//auto split. equally give win amt to both players. see, there are 2 winners here. see! now take care of it
					winnerPos = new int[status];
					int index = 0;
					winnerCount = status;
					
					for (int i = 0; i < players.length; i++){
						if (players[i] != null && !isLost(players[i]) && !isLeft(players[i])
								&& !isRemoved(players[i]) && !isToJoinNextGame(players[i])){
							if (players[i].totalScore >= 175){
								winnerPos[index] = i;
								//give the win amt here itself
								players[i].getPresence().addToWin(pot / status);
								players[i].setRUMMYWinAmt(pot / status);
								
								ClassRank crn = new ClassRank(players[winnerPos[index]].getName());
								crn.setRank(index);
								crn.setChips(players[winnerPos[index]].getPresence().getTotalWorth());
								crn.setWinAmt(pot / status);
								listWinners.add(crn);
								
								index++;
							}
						}
					}
				}
			}
			else {
				//get the rejoined players' score here itself
				for (int m = 0; m < MAXPLAYERS; m++) {
					if (players[m] != null && isRejoined(players[m])){
						players[m].totalScore = getHighestScore() + 1;
						players[m].setRUMMYStatus(statusACTIVE);
						_cat.debug("rejoining player " + m + " gets score updated here...");
					}
				}
			}
			
			//insert the entries in t_player_per_grs
			insertInDB(status > 0);
			
			if (status > 0){
				handleSitoutSitin();
			}
			
			broadcastMessage(roundWinner);
			//clear out variables for next round
			clearImpVars();
		}
		
		private void handleSitoutSitin(){
			//for supporting sitout and sitin. 
			//sitout means next game he wont join
			//sitin means next game he will be joining
			for (int m = 0; m < sitoutPlayers.size(); m++){
				int pos = sitoutPlayers.get(m);
				if (players[pos] != null && !isRemoved(players[pos]) && !isLeft(players[pos])){
					_cat.debug("active player wants to sit out. ok.");
					players[pos].setRUMMYStatus(statusSITTINGOUT);
				}
			}
			
			sitoutPlayers.clear();
			
			for (int m = 0; m < sitinPlayers.size(); m++){
				int pos = sitinPlayers.get(m);
				if (players[pos] != null && isSittingOut(players[pos])){
					_cat.debug("sitting out player wants to rejoin. ok.");
					players[pos].setRUMMYStatus(statusACTIVE);
				}
			}
			
			sitinPlayers.clear();
		}
		
		private boolean checkIfPosAWinner(int m){
			if(winnerPos == null)
				return false;
			
			for (int i = 0; i < winnerPos.length; i++){
				if (winnerPos[i] == m)
					return true;
			}
			
			return false;
		}
		
		private int determineGameOver(){
			int countActive = 0, countLess80 = 0, countWith80 = 0;
			for (int i = 0; i < players.length; i++){
				if (players[i] != null && !isLost(players[i]) && !isToJoinNextGame(players[i])
						&& !isLeft(players[i]) && !isRemoved(players[i]) && !isRejoined(players[i])
								&& !isSittingOut(players[i])
						){
					
					if (players[i].totalScore < 201){
						countActive++;
						
						if (players[i].totalScore > 174)
							countWith80++;
						else
							countLess80++;
					}
					else {
						players[i].setRUMMYStatus(statusLOST);
						_cat.debug("marking " + i + " as LOST from dgo as he has : " + players[i].totalScore);
					}
				}
			}
			
			_cat.debug("determingameover cnt actives : " + countActive);
			
			if (countActive == 1)
				return 1;
			
			//keeping countActive as 2 here.
			if (countActive >= 2){
				//for auto split
				//check if both players have more than >= 175
				if ((countLess80 == 0 && countActive == 2 && countWith80 == 2) || 
						(countActive == 3 && countLess80 == 0 && countWith80 == 3)
						){
					autoSplitOn = true;
					splitOptedFor = 2;
					return countWith80;
				}
			}
			
			if (countActive == 2){
				//for manual split, check that originally there has to be more than 2 players
				if (countPotContenders >= 3){
					manualSplitOn = true;
					return 0;
				}
			}
			
			if (countActive == 3){
				//for manual split, check that originally there has to be more than 3 players
				if (countPotContenders > 3 || (countPotContenders == 3 && numRejoins == 1)){
					//get the drops count for each player.
					// a drop count is the no of times a player can make First Drop before getting kicked out
					//so a player with 175 will have 1 drop count for adding 20 will take him to 100 and still safe
					//a player with 60 will have 2 drop counts
					//at max there can be 3 manual split winners
					int[] manual = new int[3];
					int manualWinner = 0;
					for (int i = 0; i < players.length; i++){
						if (players[i] != null && isActive(players[i]) && !isToJoinNextGame(players[i]) && !isLost(players[i])){
							int dropcount = 0;
							while ((players[i].totalScore + ((dropcount+1) * 20)) < 201)
								dropcount++;
							
							manual[manualWinner++] = dropcount;
						}
					}
					
					boolean flag = true;
					if (manualWinner == 3){
						//special check that dropcount of any 2 players can't have difference of more than 2
						for (int m = 0; m < 3 && flag; m++){
							for (int n = m+1; n < 3; n++){
								if (manual[m] > manual[n] && manual[m] - manual[n] > 2){
									flag = false;
									break;
								}
								if (manual[m] <= manual[n] && manual[n] - manual[m] > 2){
									flag = false;
									break;
								}
							}
						}
					}
					
					if (flag)
						manualSplitOn = true;
					return 0;
				}
			}
			
			return 0;
		}
		
		//for t_pool_winner
		public void saveWinners(int index) {
			_cat.debug("total players in hand : " + listWinners.size());

			// loop thru the players, find the participating players
			// insert their entries in t_player_per_grs
			DBPoolWinner grs = new DBPoolWinner();
			if (createdTournyTime != null)
				grs.setStartTime((createdTournyTime.getTime()));
			
			grs.setTournyId(index);
			// ************STARTING BATCH
			int j = -1;
			try {
				Statement grs_stat = grs.startBatch();
				for (int i = 0; i < listWinners.size(); i++) {
					if (listWinners.get(i) == null){
						_cat.debug("horrible! player null and it reached saveWinners!!!");
						continue;
					}

					String name = listWinners.get(i).getRummyProfile();
					j++;

					grs.setDisplayName(name);
					grs.setRank(listWinners.get(i).getRank());
					
					grs.setAmount(listWinners.get(i).getWinAmt());
					
					grs.save(grs_stat);
				}
				
				if (j != -1) {
					// COMMITTING BATCH
					grs.commitBatch(grs_stat);
				}
				
				_cat.debug("winners committed to tourny_winners...");
				// TBD- login session have to be updated with win and bet
			} catch (Exception ex) {
				_cat.debug("Exception - " + ex.getMessage());
			}
		}

		// to be called when game is well and truly over -- when plyr count < 2
		// and when one player's win amt >= 100
		public void insertInDB(boolean gameOverFlag) {
			_cat.debug("game over. db entry work here.");
			new Thread(){
				public void run(){
					// loop thru the players, find the participating players
					// insert their entries in tplayerpergrs
					
					if (countPotContenders == 0) {
						_cat.debug("no need to do any db work. no players.");
						return;
					}
						
					GameRunSession grs = new GameRunSession(_gid, rummygrid, GameType.RummyPoolOne);
		//					type.intVal());
					grs.setEndTime(new Date());
					grs.setStartTime(gameStartTime.getTime());
					double rake[];
					rake = Utils.integralDivide(_rake, countPotContenders);
		
					// ************STARTING BATCH
					int j = -1;
					try {
						Statement grsstat = grs.startBatch();
						for (int i = 0; i < MAXPLAYERS; i++) {
							// playersInHand
							if (players[i] == null || !isToJoinNextGame(players[i]))
								continue;
		
							RummyProfile pr = players[i];
							j++;
		
							GamePlayer gp = (GamePlayer) pr.getPresence().player();
							grs.setDisplayName(gp.name());
							grs.setPosition(pr.pos());
							grs.setPot(pot);
							double betamnt = pr.getPresence().currentRoundBet();
							grs.setStartWorth(pr.getGameStartWorth());
		
							grs.setWinAmount(0);
							// for winner only these 2 lines
							if (checkIfPosAWinner(i)) {
								// win amt will be actual win amt after accounting for
								// bet amt
								grs.setWinAmount(pr.getRUMMYWinAmt());
								grs.setEndWorth(pr.getGameStartWorth() + pr.getRUMMYWinAmt() - betamnt);
							} else {
								grs.setEndWorth(pr.getGameStartWorth() - betamnt);
							}
							grs.setBetAmt(betamnt);
							// now for all
							grs.setSessionId(gp.session());
							grs.setRake(rake[j]);
		
							grs.setMoveDet(pr.getRUMMYMovesStr());
							grs.save(grsstat);
		
							// now set the player's start worth
							players[i].setGameStartWorth(grs.getEndWorth());
						}
						// COMMITTING BATCH
						grs.commitBatch(grsstat);
						_cat.debug("grs committed...");
						// TBD- login session have to be updated with win and bet
					} catch (Exception ex) {
						_cat.debug("Exception - " + ex.getMessage());
					}
				}
			}.start();

			//if game over, then also enter in t_pool_winner
			if (gameOverFlag){
				if (winnerCount > 0) {
					for (int i = 0; i < winnerCount; i++){
						winnerString += winnerPos[i] + "'" + players[winnerPos[i]].name + "'" + (players[winnerPos[i]].getRUMMYWinAmt()) + "|";
					}
					winnerString = winnerString.substring(0, winnerString.length() - 1);
				}
				
				_cat.debug("winner string : " + winnerString);
				saveWinners(tid);
				
				//at this point of time, clear out status of players
				for (int m = 0; m < MAXPLAYERS; m++){
					if (players[m] != null && !isRemoved(players[m]) && !isLeft(players[m]) && !isSittingOut(players[m]))
						players[m].setRUMMYStatus(statusACTIVE);
				}
				
				//for t_user_eap, this is the only place
				new Thread(){
					public void run(){
						double rake[];
						rake = Utils.integralDivide(_rake, countPotContenders);
						
						for (int m = 0; m < countPotContenders; m++){
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
		}

		void addCardsToFreshPile(Card[] crs, int index) {
			if (crs == null)
				return;

			for (int i = 0; i < index; i++) {
				if (crs[i].getIndex() > -1) {
					deck.clear(crs[i].getIndex());
				}
			}
		}

		boolean checkGameOver() {
			_cat.debug("check game over for tid " + tid);
			//we check if a player has run out with all his cards.
//			if (gameOngoing) {
//				for (int i = 0; i < MAXPLAYERS; i++){
//					RummyProfile rp = (RummyProfile) players[i];
//					
//					if (rp != null && rp.getPresence() != null && isActive(rp) && (rp.allotedCards == null || rp.allotedCards.length == 0)){
//						_cat.debug("game over. cards are done and dusted.");
//						_cat.debug("and winnerrrr issssssssssssss " + i);
//						declareRoundOver(i);
//						return true;
//					}
//				}
//			}
			
//			//error checking --- first check if the fresh card pile is still alive.
//			if (_deck.cardinality() > (MAX_CARDS + 2 * NUM_DECKS)) {//should be max_cards - 2 but for testing letting it to be max_cards itself
//				System.out
//						.println("WARNING!!!! fresh pile has run out!!!!");
//				declareRoundOver(-1);// -1 will force declareroundover
//				return true;
//			}
			
			if (!gameOngoing){
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
					if (pos != -1 && gameOngoing) {
						if (players[pos].getUsingTimeBank()){
							players[pos].setTimeBank(players[pos].getTimeBankExpTime() - System.currentTimeMillis());
							players[pos].setUsingTimeBank(false);
						}
						
						_cat.debug("players no longer there");
						declareRoundOver(pos);
//						broadcastMessage(pos);
					} else {
						// for some reaons either game is not on or pos is -1
						gameOngoing = false;
					}
				} else {
					// less than 1 player on table - sad state of affairs
					gameOngoing = false;
				}

				return true;
			} else {
				return false;
			}
		}
		
		void clearImpVars(){
			deck.clear();
			indexDiscardDeck = 0;
			poolStarted = false;
			roundWinner = -1;
			currRoundResStr = "";
			nextMovePlayer = -1;
		}

		void removePlayersDisconnected() {
			for (int i = 0; i < MAXPLAYERS; i++) {
				if (players[i] != null) {
					// check if player is marked as active - then let it be.
					// else remove him
					if (isRemoved(players[i]) || isLeft(players[i])) {
						sendMessageKickedOut(players[i].pos(), 0);
						players[i] = null;
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
				if (dealer == pos) {
					players[pos + 1] = rp;
					rp.pos = pos + 1;
					// shift the dealer after game is over
					return rp.pos();
				}

				// the dealer is somewhere in the middle of players, start
				// shifting the players above the dealer
				// for (int i = pos; i > dealer; i--) {
				// players[i + 1] = players[i];
				// players[i+1].pos = i+1;
				// players[i] = null;
				//
				// //shift the nextmovePos and rummyPlayer tag
				// if (rummyPlayer == i)
				// rummyPlayer = i + 1;
				// if (nextMovePlayer == i)
				// nextMovePlayer = i + 1;
				// }
				players[pos + 1] = rp;
				players[pos + 1].pos = pos + 1;
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
			// if (players[0] == null) {
			// return -1;
			// }

			for (int i = 0; i < MAXPLAYERS; i++) {
				if (i + 1 >= MAXPLAYERS) {
					if (players[5] != null && players[0] == null)
						return 5;
				}

				if (players[i] != null && players[i + 1] == null)
					return i;
			}

			return retPos;
		}

		// Vikas
		// ****************************************************************************************************
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
//						_cat.debug("cards Pending : " + toks2[k]);
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
			if (crd.getIndex() >= 156)
				return true;

			int dummy = 0;
			dummy = rummyCardJoker.getRank();
			// first check the cut joker
			if (crd.getRank() == dummy)
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
					_cat.debug("checknig impure run wiht Ace");
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

		int findPenalty(String[] toks) {
			Card[] cards = new Card[toks.length];
			for (int i = 0; i < toks.length; i++) {
				cards[i] = new Card(toks[i]);
			}

			return computePoints2(cards);
		}

		String countRunsAsArranged(String cardsstr) {
			int pureRuns = 0, impureRuns = 0, numSets = 0, tanalaCount = 0;
			String pureRunGroup = "";
			String allRunsSetsGroup = "";
			boolean runGT4 = false;
			
			//check if all 13 cards are being sent by client
			int numCards = 0;
			String[] toks1 = cardsstr.split("\\|");
			for (int i = 0; i < toks1.length; i++) {
				String[] toks2 = toks1[i].split("\\`");
				numCards += toks2.length;
			}
			
			if (numCards < 13){
				_cat.debug("all 13 cards have to be sent!");
				return null;
			}
			
			// these 2 variables only for pure runs and tanala - to be used if
			// only one pure run is to be exempted
			int highPenalty = 0;
			int highPenIndex = -1;

			toks1 = cardsstr.split("\\|");
			for (int i = 0; i < toks1.length; i++) {
				boolean somethingfound = false;
				_cat.debug("to check : " + toks1[i]);
				String[] toks2 = toks1[i].split("\\`");
				// got the cards of one group
				// there can be these cases - a pure run, an impure run, a pure
				// set, an impure set, a tanala
				if (toks2.length == 3) {
					// could be a tanala
					if (toks2[0].compareToIgnoreCase(toks2[1]) == 0
							&& toks2[0].compareToIgnoreCase(toks2[2]) == 0) {
						tanalaCount++;
						pureRunGroup += i + "'";
						somethingfound = true;
						_cat.debug("tanala : " + toks1[i]);
						int pen = findPenalty(toks2);
						if (pen > highPenalty) {
							highPenalty = pen;
							highPenIndex = i;
						}
					}
				}

				if (toks2.length >= 3 && !somethingfound) {
					// check for run
					if (CheckPureRuns(toks2)) {
						pureRuns++;
						pureRunGroup += i + "'";
						somethingfound = true;
						if (toks2.length >= 4)
							runGT4 = true;
						_cat.debug("pure run : " + toks1[i]);
						int pen = findPenalty(toks2);
						if (pen > highPenalty) {
							highPenalty = pen;
							highPenIndex = i;
						}
					} else if (CheckImPureRuns(toks2)) {
						impureRuns++;
						allRunsSetsGroup += i + "'";
						somethingfound = true;
						if (toks2.length >= 4)
							runGT4 = true;
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
				_cat.debug("pur run grp : " + pureRunGroup
						+ " , others group : " + allRunsSetsGroup);
			}

			// we have gone thru cards
			if (tanalaCount + pureRuns < 1) {
				// oooh, hand not valid, ooh, run like chicken
				return null;
			} else {
				// check now if there are 2 runs
				if (tanalaCount + pureRuns + impureRuns >= 2) {//runGT4 - removing this condition...
					// valid hand - we meet our condition of having 2 runs one
					// of which is definitely a pure run
					pureRunGroup += allRunsSetsGroup;
					if (!pureRunGroup.isEmpty()) {
						pureRunGroup = pureRunGroup.substring(0,
								pureRunGroup.length() - 1);
					}
					return pureRunGroup;
				} else {
					// not valid hand, so just send the pure run to be removed
					// take care that only 1 run is to be exempted. if there are
					// 2 or 3 pure runs, take the one with highest penalty
					pureRunGroup = highPenIndex + "";
					return pureRunGroup;
				}
			}
		}

		int[] checkValidCards2(Card[] cards, String cardsStrFrmClnt) {

			int[] resTemp = new int[3];// 0 holds the points, 1 holds 1 if 1st
										// run
										// is valid else 0, 2 holds 1 if 2nd run
										// is
										// valid else 0

			// if the cards string from player is empty, most likely he/she
			// didn't send it - apply penalty of 120 and let it be
			if (cardsStrFrmClnt.isEmpty()) {
				resTemp[0] = 80;
				resTemp[1] = 0;
				resTemp[2] = 0;
				return resTemp;
			}

			_cat.debug("cards from client loser : "  + cardsStrFrmClnt);
			String restrVal = countRunsAsArranged(cardsStrFrmClnt);
			if (restrVal != null && !restrVal.isEmpty()) {
				// we do have 1st life.
				// we have 1st life but no second life - remove cards of
				// pure runs
				Card[] cardsPenal = removeAllCardsOfRun2(cardsStrFrmClnt,
						restrVal);

				resTemp[0] = computePoints2(cardsPenal);
				resTemp[1] = 1;
				resTemp[2] = 0;
				return resTemp;
			} else {
				// no first life - penalty on all cards
				resTemp = new int[3];
				resTemp[0] = computePoints2(cards);
				resTemp[1] = 0;
				resTemp[2] = 0;
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

				if (cards[i].getRank() == rummyCardJoker.getRank()) {
					val += 0;
				} else {
					dummy = cards[i].getHighBJRank();
					if (dummy > 10 || dummy == 0)
						dummy = 10;
					val += dummy;
				}
			}

			if (val > 80)
				val = 80;// this is the max that a player has to pay
			return val;
		}

		// Vikas
		// ****************************************************************************************************

		public void run() {
			while (keepServicing) {
				String moveStr = null;
				try {
					Presence p = fetch(tid);
					moveStr = p.getLatestMoveStr();
					_cat.debug("movestr from run : " + moveStr);
					if (moveStr != null) {
						processStr(moveStr, p);
					}
				} catch (NoSuchElementException e) {
				} catch (Exception ex) {
				}

				// now check if game is going to be ended - someone made a valid
				// declaration and we are waiting for all cards messages
				// idPlayerValidDeclared
				if (gameOngoing
						&& idPlayerValidDeclared != -1
						&& (System.currentTimeMillis() - nextMoveExpTime) > 20000) { 
					declareRoundOver(idPlayerValidDeclared);
//					broadcastMessage(idPlayerValidDeclared);
					idPlayerValidDeclared = -1;
					nextMovePlayer = -1;
				}
				
				if (gameOngoing
						&& manualSplitProcessOn
						&& (System.currentTimeMillis() - nextMoveExpTime) > 20000){
					//manual split option started. someone timed out. horrible.
					manualSplitProcessOn = false;
					manualSplitResponders = -1;
					manualSplitOn = false;
					
					nextMovePlayer = holdNextMovePlayer;
					holdNextMovePlayer = -1;
					nextMovesAllowed = holdNextMoves;
					nextMoveExpTime = System.currentTimeMillis();
					broadcastMessage(-1);
				}

				// now check if someone has timed out his moves
				if (gameOngoing
						&& (System.currentTimeMillis() - nextMoveExpTime) > 30000) {
					if (nextMovePlayer != -1 && players[nextMovePlayer] != null) {
						//3rd Aug 2017 - changes for disconnect protection using time bank
						RummyProfile rp = players[nextMovePlayer];
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
				}

				// maintenance job - shall we create a separate thread for it?
				// maintenance job - if there is only one player on a table
				boolean toSendM = false;
				for (int m = 0; m < MAXPLAYERS && !gameOngoing; m++) {
					if (players[m] != null
							&& System.currentTimeMillis() - players[m].rummyLeftTableTime > 8000
							&& (isLeft(players[m]))) {
						// removed condition for waiting player to make a move -
						// that was creating issues with players who are waiting
						// to join next game
						// System.out
						// .println("nothing from this player, kick him out : "
						// + players[m].getName());
						// before sending the message we should check if client
						// is showing the result window
						// if so, this message would curtail the beautiful,
						// rapturous experience of a winning player
						sendMessageKickedOut(m, 2);
						toSendM = true;
						// remove the player
						removeFromTable(m);
						players[m] = null;
						winnerString = "";
						currRoundResStr = "";
						combinedRoundRes = "";
						roundNum = -1;
						roundWinner = -1;
					}
				}
				
				if (gameOngoing && toSendM){
					resetTable(true);
				}

				// start new round after 5 seconds of result display
				
				if (gameOngoing
						&& !poolStarted
						&& getCntStatusEligiblePlayers() >= 2
						&& (System.currentTimeMillis() - nextMoveExpTime) > 10000) {
					_cat.debug("from run starting new round");
					
					initGame();
					startNewRound();
				}
				
				//error condition - for some reason a player left in the middle of game. end the game now.
//				if (gameOngoing
//						&& nextMovePlayer == -1
//						&& getCntStatusActivePlayers() < 2
//						) {
//					checkGameOver();
//					initGame();
//				}

				if (!gameOngoing && nextMoveExpTime != -1
						&& System.currentTimeMillis() - nextMoveExpTime > 10000) {
					int countPlayers = getCountActivePlayers() + getCountSitInReqPlayers();
					if (getCountTotalPlayers() >= 2 && counterGameNotStarted < 5) {
						//giving one minute to let other players join in
						if (countPlayers >= 2){
							fixDealerFirst();
						}
						else {
							counterGameNotStarted++;
							resetTable(true);
							if (countPlayers == 1)
								broadcastMessage(-1);
						}
					} else {
						// clear out the jokers for it creates a wrong
						// impression
						rummyCardJoker = null;
						discardCard = null;
						dealer = -1;
						rummyPlayer = -1;

						if (System.currentTimeMillis() - nextMoveExpTime > 15000) {
							counterGameNotStarted = 0;
						}
						
						// remove the removed players now
						if (System.currentTimeMillis() - nextMoveExpTime > 60000) {
							for (int m = 0; m < MAXPLAYERS; m++) {
								if (players[m] != null) {
									sendMessageKickedOut(m, 2);
									players[m] = null;
								}
							}
							winnerString = "";
							currRoundResStr = "";
							combinedRoundRes = "";
							roundNum = -1;
							roundWinner = -1;
							nextMoveExpTime = -1;
							counterGameNotStarted = 0;
						}

					}
				}

				if (!gameOngoing) {
					if (System.currentTimeMillis() - nextMoveExpTime > 120000) {
						
						for (int b = 0; b < observers.size(); b++) {
							RummyProfile pro = (RummyProfile) observers.get(b);
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
			if (deck.cardinality() >= MAXCARDS) {
				_cat.debug("WARNING!!!! fresh pile has run out!!!!");
				addCardsToFreshPile(discardDeck, indexDiscardDeck);
				discardDeck = new Card[65];

				// keep the same discard card - don't change it
				deck.set(discardCard.getIndex());

				indexDiscardDeck = 0;
				discardDeck[indexDiscardDeck++] = discardCard;

				// also mark the rummy card joker - we can't give it if we have
				// only one deck
				deck.set(rummyCardJoker.getIndex());
			}

			int k = Rng.nextIntBetween(0, 100);
			if (k == 73 || k == 69 || deck.cardinality() >= 50) {
				// give a joker if it is possible
				if (totalJokersAlreadyDealt < NUMDECKS) {
					totalJokersAlreadyDealt++;
					deck.set(160 + totalJokersAlreadyDealt);
					printedJokerDealt = true;
					return 160 + totalJokersAlreadyDealt;
				}
			}

			// also put a condition on deck size - if 1/2 deck is gone and no
			// printed joker is dealt, deliberately give a
			if (deck.cardinality() >= (int) (MAXCARDS * 0.4)
					&& deck.cardinality() < (int) (MAXCARDS * 0.5)
					&& !printedJokerDealt) {
				if (totalJokersAlreadyDealt < NUMDECKS) {
					totalJokersAlreadyDealt++;
					deck.set(160 + totalJokersAlreadyDealt);
					printedJokerDealt = true;
					return 160 + totalJokersAlreadyDealt;
				}
			}

			if (deck.cardinality() >= (int) (MAXCARDS * 0.6)
					&& deck.cardinality() < (int) (MAXCARDS * 0.7)
					&& !printedJokerDealt) {
				if (totalJokersAlreadyDealt < NUMDECKS) {
					totalJokersAlreadyDealt++;
					deck.set(160 + totalJokersAlreadyDealt);
					printedJokerDealt = true;
					return 160 + totalJokersAlreadyDealt;
				}
			}

			if (deck.cardinality() >= (int) (MAXCARDS * 0.8)
					&& deck.cardinality() < (int) (MAXCARDS * 0.9)
					&& !printedJokerDealt) {
				if (totalJokersAlreadyDealt < NUMDECKS) {
					totalJokersAlreadyDealt++;
					deck.set(160 + totalJokersAlreadyDealt);
					printedJokerDealt = true;
					return 160 + totalJokersAlreadyDealt;
				}
			}

			int rand = Rng.nextIntBetween(0, MAXCARDS);
			while (deck.get(rand)) {
				rand = Rng.nextIntBetween(0, MAXCARDS);
			}
			deck.set(rand);
			// _cat.debug("found this card : " + rand +
			// " , max cards : "
			// + MAXCARDS);

			if (deck.cardinality() >= (int) (MAXCARDS * 0.5)
					|| deck.cardinality() >= (int) (MAXCARDS * 0.7)
					|| deck.cardinality() >= (int) (MAXCARDS * 0.9))
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
			while (deck.get(rand)) {
				rand = Rng.nextIntBetween(0, 51);
			}
			deck.set(rand);
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
			_cat.debug("player moves : tid : " + tid + " , pos : " + pos
					+ ", moveid : " + moveId + ",type : " + type
					+ " , nextmoveplayer : " + nextMovePlayer);

			// first handle the case of sit in request
			if (moveId == moveRUMMYJOINED) {
				// definitely a sit in request ---
				//check here if game is in declare mode expecting losers to send in their card string
				//if so, we can't let this player join the table - he/she has to wait till dust settles
				if (nextMovePlayer != 111 && nextMovePlayer != 333)
					handleGameJoinReq(pos, p.name());
				return;
			}

			if (players[pos] == null || players[pos].getPresence() == null) {
				System.out
						.println("wrong wrong wrong rummy profile or presence missing");
				return;
			}
			
			if (moveId == move_RUMMY_CHAT) {
				lastMove = move_RUMMY_CHAT;
				lastMovePos = pos;
				lastMoveString = "Chat";
				chatOn = 1;
				chatMessage = "Pos " + (pos + 1) + ":" + cardsDet;
				broadcastMessage(-1);
				chatOn = 0;
				chatMessage = "";
				return;
			}
			
			if (moveId == move_RUMMY_SITOUT) {
				lastMove = move_RUMMY_SITOUT;
				lastMovePos = pos;
				lastMoveString = "SitOutNextGame";
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " wants to sitout next game.";
				if (isSittingOut(players[pos])){
					_cat.debug("already sitting out");
					return;
				}
				
				if (!sitoutPlayers.contains(pos))
					sitoutPlayers.add(pos);
				
				if (!gameOngoing){
					handleSitoutSitin();
				}
				
				broadcastMessage(-1);
				chatMessage = "";
				return;
			}
			
			if (moveId == move_RUMMY_SITIN) {
				lastMove = move_RUMMY_SITIN;
				lastMovePos = pos;
				lastMoveString = "RejoinNextGame";
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " wants to rejoin next game.";
				if (!isSittingOut(players[pos])){
					_cat.debug("not sitting out so why?");
					return;
				}

				if (!sitinPlayers.contains(pos))
					sitinPlayers.add(pos);
				
				if (!gameOngoing){
					handleSitoutSitin();
				}
				
				broadcastMessage(-1);
				chatMessage = "";
				return;
			}

			if (!gameOngoing) {
				System.out
						.println("game not running, ignore all messages - let them all die!!!!!");
				return;
			}

			RummyProfile prp = players[pos];

			newCardAdded = "";
			if (nextMovePlayer == 111 && moveId != moveRUMMYDECLARELOSER) {
				System.out
						.println("no moves other than declare loser allowed!!!");
				sendErrorMessage(pos, 3);
				return;
			} 
			else if (nextMovePlayer == 333 && moveId != moveRUMMYMANUALSPLIT) {
				_cat.debug("no moves other than manual split allowed!!!");
				sendErrorMessage(pos, 3);
				return;
			} 
			else {
				if (nextMovePlayer != 111 && pos != nextMovePlayer && nextMovePlayer != 333
						&& moveId != moveRUMMYDECLARE) {
					_cat.debug("no moves allowed from this pos!!!");
					sendErrorMessage(pos, 0);
					return;
				}
				if ((moveId & nextMovesAllowed) != moveId
						&& nextMovePlayer != 111 && nextMovePlayer != 333) {
					System.out
							.println("these moves not allowed from this pos!!!");
					sendErrorMessage(pos, 0);
					return;
				}
			}

			players[pos].rummyLeftTableTime = -1;

			// update the rummyLastMoveTime for this player
			players[pos].rummyLastMoveTime = System.currentTimeMillis();

			if (moveId == moveRUMMYDECLARELOSER) {
				if (pos == idPlayerValidDeclared)
					return;
				
				lastMove = moveRUMMYDECLARELOSER;
				lastMovePos = pos;
				lastMoveString = "Declare";
				prp.setRUMMYMovesStr("&GameDeclareLoser^" + cardsDet);
				prp.setRUMMYMoveId(moveRUMMYDECLARELOSER);
				
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " declared";

				// just store the cards arrangement string from player
				prp.cardsStrFromClient = cardsDet;

				countPlayerResponseDeclareLoser--;
				if (countPlayerResponseDeclareLoser <= 0) {
					// end the game here
					declareRoundOver(idPlayerValidDeclared);
//					broadcastMessage(idPlayerValidDeclared);
					idPlayerValidDeclared = -1;
					nextMovePlayer = -1;
				}
			}

			if (moveId == moveRUMMYDECLARE) {
				lastMove = moveRUMMYDECLARE;
				lastMovePos = pos;
				lastMoveString = "Declare:";
				prp.setRUMMYMovesStr("&GameDeclare^" + cardsDet);
				prp.setRUMMYMoveId(moveRUMMYDECLARE);
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);
				
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " tried declare.";

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
							sendErrorMessage(pos, 1);
							return;
						}
					}
					// get rid of card - index is in type
					int[] newCards = new int[13];
					int j = 0;
					boolean found = false;
					for (int i = 0; i < 14; i++) {
						if (prp.allotedCards[i].getIndex() != typeIndex
								|| found) {
							newCards[j++] = prp.allotedCards[i].getIndex();
						} else {
							found = true;
						}
					}
					prp.allotedCards = new Card[13];
					for (int i = 0; i < newCards.length; i++) {
						Card crd = new Card(newCards[i]);
						crd.setIsOpened(true);
						prp.allotedCards[i] = crd;
						// _cat.debug("card added : " + crd.toString());
					}
				} else {
					// check if teh player is deliberately sending declare move
					if ((prp.getRUMMYStatus() & statusACTIVE) != statusACTIVE) {
						return;
					}
				}

				// check if cards are indeed valid
				boolean flagGameOver = false;
				if (prp.markCheckingCards)
					return;

				prp.markCheckingCards = true;
				idPlayerChecked = pos;
				prp.cardsStrFromClient = cardsDet;
				int[] result = checkValidCards2(prp.allotedCards,
						prp.cardsStrFromClient);
				prp.markCheckingCards = false; // has to be done because
												// checkValidCards takes time to
												// complete and we don't want
												// multiple calls
				_cat.debug("penalyt : " + result[0] + ", first life : "
						+ result[1] + " , 2nd life : " + result[2]);
				
				//always discard card, even if it is a failed bid to declare
				discardCard = new Card(typeIndex);
				discardCard.setIsOpened(true);
				discardDeck[indexDiscardDeck++] = discardCard;
				
				if (result[0] == 0 && result[1] == 1)
					flagGameOver = true;

				if (flagGameOver) {
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " made valid declaration";
					
					lastMoveString += "Valid";
					prp.setRUMMYMovesStr("^ValidDeclare");
					idPlayerValidDeclared = pos;
					// for each player, now set the move as declare loser. now
					// keep a count of players from whom messages have to come
					nextMovesAllowed = moveRUMMYDECLARELOSER;
					nextMovePlayer = 111;
					broadcastMessage(-1);

					nextMoveExpTime = System.currentTimeMillis();

					// now keep a track of how many active players are going to
					// respond back
					for (int m = 0; m < MAXPLAYERS; m++) {
						if (players[m] != null
								&& m != pos
								&& (isActive(players[m])
										&& !isRemoved(players[m]) && !isFolded(players[m]))) {
							// only non folded players are supposed to make
							// moves
							countPlayerResponseDeclareLoser++;
						}
					}

					if (countPlayerResponseDeclareLoser == 0) {
						// no other player to wait on - end this drama now.
						declareRoundOver(idPlayerValidDeclared);
//						broadcastMessage(idPlayerValidDeclared);
						idPlayerValidDeclared = -1;
						nextMovePlayer = -1;
					}

					return;
				} else {
					// apply penalty on player, fold him, continue game
					lastMoveString += "Invalid";
					prp.setRUMMYMovesStr("^ValidFail");
					prp.setRUMMYStatus(statusFOLDED);
					prp.allotedCards = null;
					prp.setRUMMYPoints(80);
					if (!checkGameOver()) {
						if (pos == nextMovePlayer) {
							nextMovesAllowed = moveRUMMYDECLARE
									| moveRUMMYNEWCARD | moveRUMMYFOLD;
							if (manualSplitOn)
								nextMovesAllowed |= moveRUMMYMANUALSPLIT;
							
							nextMovePlayer = getRightOfPlayer(nextMovePlayer);
						}
						broadcastMessage(-1);
					}
				}
			}
			
			//special move for pool rummy. manual split. 
			if (moveId == moveRUMMYMANUALSPLIT){
				//manual split. every one has to agree
				nextMoveExpTime = System.currentTimeMillis();
				
				if (!manualSplitProcessOn){
					//very first manual split request
					//send message to others
					manualSplitResponders = getCountActivePlayers() - 1;
					manualSplitProcessOn = true;
					
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " sent manual split request.";
					
					holdNextMovePlayer = nextMovePlayer;
					holdNextMoves = nextMovesAllowed;
					nextMovePlayer = 333;
					nextMovesAllowed = moveRUMMYDECLARE | moveRUMMYNEWCARD | moveRUMMYMANUALSPLIT;
					broadcastMessage(-1);
				}
				else if (nextMovePlayer == 333) {
					int typ = Integer.parseInt(type);
					if (typ > 0){
						chatOn = 0;
						chatMessage = "Pos " + (pos + 1) + " confirmed manual split.";
						manualSplitResponders--;
						if (manualSplitResponders <= 0) {
							handleManualSplit();
						}
						else {
							_cat.debug("still waiting for others to respond...");
						}
					}
					else {
						//someone said NO. stop this nonsense right now
						manualSplitProcessOn = false;
						manualSplitResponders = -1;
						manualSplitOn = false;
						
						nextMovePlayer = holdNextMovePlayer;
						holdNextMovePlayer = -1;
						
						nextMovesAllowed = holdNextMoves;
						holdNextMoves = -1;
						
						broadcastMessage(-1);
					}
				}
			}

			// if (moveId == moveRUMMYRUMMYCUT) {
			// //this move can only come from designated player rummyPlayer, no
			// one else can send it
			// lastMove = moveRUMMYNEWCARD;
			// lastMovePos = pos;
			// lastMoveString = "RummyCut";
			// prp.setRUMMYMoveId(moveRUMMYNEWCARD);
			// prp.setRUMMYStatus(statusACTIVE);
			// nextMoveExpTime = System.currentTimeMillis();
			//
			// //give cards to all players
			// for (int m = 0; m < MAXPLAYERS; m++) {
			// if (players[m] != null && players[m].getPresence() != null) {
			// for (int i = 0; i < 13; i++) {
			// int randCard = drawCard();
			// Card cr = new Card(randCard);
			// cr.setIsOpened(true);
			// players[m].allotedCards[i] = cr;
			// }
			// }
			// }
			//
			// //set the next move player - it will be the one on dealer's left
			// nextMovePlayer = getNextActivePos(dealer);
			// nextMovesAllowed = moveRUMMYDECLARE | moveRUMMYNEWCARD |
			// moveRUMMYFOLD;
			// broadcastMessage(-1);
			// }

			if (moveId == moveRUMMYNEWCARD) {
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
						+ prp.allotedCards.length);

				// if client sends this message again then 2nd and subsequent
				// message have to be dropped
				if (prp.allotedCards.length >= 14) {
					System.out
							.println("already gave one card, how many does it want more !!!");
					sendErrorMessage(pos, 0);
					return;
				}

				// use type to find if it is frsh pile card (0) or discard (1)
				Card cr = null;
				if (typeO == 0) {
					lastMoveString = "FreshCard";
					int randCard = drawCard();
					cr = new Card(randCard);
					cr.setIsOpened(true);
					prp.setRUMMYMovesStr("&GetFresh^" + cr.toString());
					newCardAdded = cr.toString();
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " chose fresh card.";
				} else {
					cr = new Card(discardCard.getIndex());
					cr.setIsOpened(true);
					
					//added on 20th Sep 2017 - 
					//joker card in discarded cards can't be taken
					if (determineIfJoker(cr)){
						_cat.debug("can't take joker card from discards...");

						//check now if this is the very first move of very first player
						//then it is allowed
						boolean firstMove = true;
						for (int k = 0; k < MAXPLAYERS; k++){
							if (players[k] != null && players[k].firstMoveDone)
								firstMove = false;
						}
						
						if (!firstMove) {
							sendErrorMessage(pos, 4);
							return;
						}
					}
					
					lastMoveString = "DiscardCard";
					prp.setRUMMYMovesStr("&GetDisc^" + discardCard.toString());
					
					discardDeck[indexDiscardDeck - 1] = null;
					indexDiscardDeck--;
					if (indexDiscardDeck > 0)
						discardCard = discardDeck[indexDiscardDeck - 1];
					else
						discardCard = null;

					newCardAdded = cr.toString();
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " chose discarded card.";
				}

				_cat.debug("came here : " + lastMoveString
						+ " , card : " + cr.toString());
				
				prp.firstMoveDone = true;
//				nextMoveExpTime = System.currentTimeMillis();
				lastMove = moveRUMMYNEWCARD;
				lastMovePos = pos;
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);
				
				prp.setRUMMYMoveId(moveRUMMYNEWCARD);
				prp.setRUMMYStatus(statusACTIVE);
				
				Card[] clonedCards = prp.allotedCards.clone();
				prp.allotedCards = new Card[14];
				for (int i = 0; i < 13; i++) {
					prp.allotedCards[i] = clonedCards[i];
				}
				prp.allotedCards[13] = cr;

				// no need to change player
				if (!checkGameOver()) {
					nextMovesAllowed = moveRUMMYDECLARE | moveRUMMYDISCARD;
					if (manualSplitOn)
						nextMovesAllowed |= moveRUMMYMANUALSPLIT;
					broadcastMessage(-1);
				}
			}

			if (moveId == moveRUMMYDISCARD) {
				nextMoveExpTime = System.currentTimeMillis();
				lastMove = moveRUMMYDISCARD;
				lastMovePos = pos;
				lastMoveString = "Discard:" + type;
				prp.setRUMMYStatus(statusACTIVE);
				prp.setRUMMYMoveId(moveRUMMYDISCARD);
				
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
						sendErrorMessage(pos, 1);
						return;
					}
				}
				
				// get rid of card - index is in type
				// get rid of card - index is in type
				int[] newCards = new int[13];
				int j = 0;
				boolean found = false;
				for (int i = 0; i < 14; i++) {
					if (prp.allotedCards[i].getIndex() != typeIndex || found) {
						newCards[j++] = prp.allotedCards[i].getIndex();
					} else {
						found = true;// found 1st instance of this card being
										// removed. if there are 2 cards and
										// player wants to get rid of 1, then
										// other should stay.
					}
				}
				prp.allotedCards = new Card[13];
				for (int i = 0; i < newCards.length; i++) {
					Card crd = new Card(newCards[i]);
					crd.setIsOpened(true);
					prp.allotedCards[i] = crd;
					// _cat.debug("card added : " + crd.toString());
				}

				discardCard = new Card(typeIndex);
				discardCard.setIsOpened(true);
				discardDeck[indexDiscardDeck++] = discardCard;
				
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " discarded " + discardCard.toString();
				
				//remember the card discarded
				prp.setRUMMYMovesStr("&Discard");
				if (typeIndex >= 161)
					prp.setRUMMYMovesStr("^JO");
				else
					prp.setRUMMYMovesStr("^" + discardCard.toString());

				if (!checkGameOver()) {
					nextMovePlayer = getRightOfPlayer(nextMovePlayer);
					nextMovesAllowed = moveRUMMYDECLARE | moveRUMMYNEWCARD
							| moveRUMMYFOLD;
					if (manualSplitOn)
						nextMovesAllowed |= moveRUMMYMANUALSPLIT;
					broadcastMessage(-1);
				}
			}

			if (moveId == moveRUMMYFOLD) {
				nextMoveExpTime = System.currentTimeMillis();
				lastMove = moveRUMMYFOLD;
				lastMovePos = pos;
				lastMoveString = "Folded";
				prp.setRUMMYMovesStr("&Pack");
				prp.setRUMMYStatus(statusFOLDED);
				prp.setRUMMYMoveId(moveRUMMYFOLD);
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);
				
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " folded.";

				if (prp.firstMoveDone)
					prp.setRUMMYPoints(50);
				else {
					prp.setRUMMYPoints(25);
					// player didn't even play one card, so put his cards in
					// fresh pile
					addCardsToFreshPile(prp.allotedCards, 13);
				}
				prp.allotedCards = null;

				if (!checkGameOver()) {
					// game not over
					nextMovePlayer = getRightOfPlayer(nextMovePlayer);
					nextMovesAllowed = moveRUMMYDECLARE | moveRUMMYNEWCARD
							| moveRUMMYFOLD;
					if (manualSplitOn)
						nextMovesAllowed |= moveRUMMYMANUALSPLIT;
					broadcastMessage(-1);
				}
			}
		}
		
		private void handleManualSplit(){
			//get the drops count for each player.
			// a drop count is the no of times a player can make First Drop before getting kicked out
			//so a player with 175 will have 1 drop count for adding 20 will take him to 100 and still safe
			//a player with 60 will have 2 drop counts
			//at max there can be 3 manual split winners
			int[] manual = new int[3];
			int[] manualPos = new int[3];
			int manualWinner = 0, totaldrops = 0;
			for (int i = 0; i < players.length; i++){
				if (players[i] != null && isActive(players[i]) && !isToJoinNextGame(players[i])
						&& !isLost(players[i])
						){
					int dropcount = 1;
					while ((players[i].totalScore + ((dropcount) * 20)) < 201)
						dropcount++;
					
					manualPos[manualWinner] = i;
					manual[manualWinner++] = dropcount;
					totaldrops += dropcount;
					_cat.debug("pos : " + i + ", dropcount : " + dropcount + ", total : " + totaldrops);
				}
			}
			
			double newPot = pot;
			//now the calculation of how prize money is to be distributed.
//			for (int m = 0; m < manualWinner; m++){
//				players[manualPos[m]].getPresence().addToWin(manual[m] * buyIn);
//				newPot -= manual[m] * buyIn;
//				players[manualPos[m]].setRUMMYWinAmt(manual[m] * buyIn);
//			}
			
			if (newPot > 0){
				//this amount now has to be equally distributed among winners
				double amt = newPot / totaldrops;
				for (int m = 0; m < manualWinner; m++){
					players[manualPos[m]].getPresence().addToWin(amt  * manual[m]);
					players[manualPos[m]].setRUMMYWinAmt(amt * manual[m]);
				}
			}
			
			winnerPos = new int[manualWinner];
			winnerCount = manualWinner;
			if (manualWinner == 3)
				winnerPos = manualPos;
			else {
				winnerPos[0] = manualPos[0];
				winnerPos[1] = manualPos[1];
			}
			
			for (int m = 0; m < manualWinner; m++){
				ClassRank crn = new ClassRank(players[manualPos[m]].getName());
				crn.setRank(m);
				crn.setChips(players[manualPos[m]].getPresence().getTotalWorth());
				crn.setWinAmt(players[manualPos[m]].getRUMMYWinAmt());
				listWinners.add(crn);
			}
			
			gameOngoing = false;
			splitOptedFor = 1;
			insertInDB(true);
			
			handleSitoutSitin();
			
			broadcastMessage(-1);
			clearImpVars();
			_cat.debug("FROM MANUAL SPLIT. HOW IS THIS EVEN POSSIBLE");
		}

		public void fixDealerFirst() {
			if (gameOngoing)
				return;
				
			resetTable(true);
			
			nextMoveExpTime = System.currentTimeMillis();
			
			int active = getCntStatusEligiblePlayers();
			if (active <= 1){
				//error condition
				_cat.debug("ERRROR : game can't be started. not enough players");
				broadcastMessage(-1);
				return;
			}
			
			lastMovePos = -1;
			lastMove = 0;
			lastMoveString = "";
			
			cardDealingOrder = "";
			
			winnerString = "";
			
		//	initGame();

			if (true) {

				deck.clear();
				fixingDealerOngoing = true;
				
				counterGameNotStarted = 0;

				for (int m = 0; m < MAXPLAYERS; m++) {
					if (players[m] != null
							&& players[m].getPresence() != null
							&& isActive(players[m])) {
						players[m].fixPosCard = drawCardOneDeck();
					}
				}

				int least = 999, leastCardBearer = -1;
				for (int m = 0; m < MAXPLAYERS; m++) {
					if (players[m] != null
							&& players[m].getPresence() != null && isActive(players[m])) {
						if (players[m].fixPosCard % 13 < least) {
							least = players[m].fixPosCard % 13;
							leastCardBearer = m;
						} else if (players[m].fixPosCard % 13 == least) {
							// if the ranks are same, then check for suits
							if (leastCardBearer != -1) {
								if (players[m].fixPosCard < players[leastCardBearer].fixPosCard) {
									leastCardBearer = m;
								}
							}
						}
					}
				}
				
				chatOn = 0;
				chatMessage = "Dealer fixed.";

				dealer = leastCardBearer;
				rummyPlayer = getRightOfPlayer(dealer);
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
				countHandsAfterDealerFix = 0;
			} 

			startGameIfPossible();
		}

		public void startGameIfPossible() {
			if (gameOngoing)
				return;

			// based on sitting players, let us see if we can start a game
			try {
				Thread.currentThread().sleep(1000);
			} catch (InterruptedException ee) {
				// continue
			}

			// game can begin ---
			// now clear out the all in players list
			
			int countPlayersInit = getCountActivePlayers();
			_cat.debug("startgame - " + countPlayersInit);
			if (countPlayersInit >= 2) {
				gameOngoing = true;

				if (countPlayersInit > 4)
					NUMDECKS = 3;
				else if (countPlayersInit > 2)
					NUMDECKS = 2;
				else
					NUMDECKS = 1;

				MAXCARDS = 52 * NUMDECKS - 1;
				
				initGame();
				
				combinedRoundRes = "";
				
				splitOptedFor = 0;
				
				for (int m = 0; m < MAXPLAYERS; m++) {
					if (players[m] != null) {
						players[m].totalScore = 0;
						_cat.debug("for m " + m + " clearing out totalsocre");
					}
				}
				
				pot = 0;
				
				numRejoins = 0;
				countPotContenders = 0;
				names = new String[MAXPLAYERS];
				
				// now initialize the variables
				for (int m = 0; m < MAXPLAYERS; m++) {
					if (players[m] != null
					// && players[m].getPresence() != null
							&& !isToJoinNextGame(players[m])
							&& !isSittingOut(players[m])
							&& !isLost(players[m])
					) {
						players[m].firstMoveDone = false;
						players[m].allotedCards = new Card[13];
						players[m].setRUMMYWinAmt(0);
						
						names[countPotContenders++] = players[m].name;
						
						if (!isRemoved(players[m])) {
							players[m].setRUMMYStatus(statusACTIVE);
							players[m].getPresence().resetRoundBet();
							players[m].getPresence().currentRoundBet(buyIn * POINTSPERRUPEE);
						}
					}
				}
				
				pot = countPotContenders * buyIn * POINTSPERRUPEE;
				_rake = RAKEPERCENTAGE * pot;
				pot = pot - _rake;
				_cat.debug("pot : " + pot + " , rake : " + _rake + " , countcontenderos : " + countPotContenders);
				
				amtWonString = "";
				
				listWinners = new ArrayList<ClassRank>(countPotContenders);
				
				createdTournyTime = Calendar.getInstance();

				idPlayerValidDeclared = -1;
				idPlayerChecked = -1;
				
				manualSplitOn = false;
				autoSplitOn = false;

				// now set teh cardDealingOrder starting from right of dealer -
				// haha - right means decrement by 1
				// but first check if dealer is still pointing to a valid player
				// - if not, shift it now
				if (dealer == -1){
					for (int m = 0; m < MAXPLAYERS; m++){
						//first player will be dealer
						if (players[m] != null)
							dealer = m;
					}
				}
				if (players[dealer] == null || isRemoved(players[dealer]) || isSittingOut(players[dealer])
						|| isLeft(players[dealer]) || isLost(players[dealer])) {
					getNextActivePos(dealer);
				}

				rummyPlayer = getRightOfPlayer(dealer);
				
				rummygrid = setNextGameRunId();

				roundNum = 0;
				startNewRound();
			}
			else {
				gameOngoing = false;
				dealer = -1;
				rummyPlayer = -1;
				removePlayersDisconnected();
				// for the unfortunate player who might be seated on teh table
				if (countPlayersInit == 1)
					broadcastMessage(-1);
			}
		}

		public void startNewRound() {
			lastRespTime = System.currentTimeMillis();
			
			_cat.debug("from start new round");
			
			poolStarted = true;
			
			roundNum++;

//			drawString = "";
			currRoundResStr = "";
			roundWinner = -1;
//			rummygrid = setNextGameRunId();
			_cat.debug("removing disconnected players from here...");
			removePlayersDisconnected();
			
			resetTable(false);
			
			if (checkGameOver()){
				_cat.debug("new round can't be started as player count too less");
				return;
			}

			lastRespTime = System.currentTimeMillis();

			// nextMovesAllowed = moveRUMMYDECLARE | moveRUMMYNEWCARD |
			// moveRUMMYFOLD;
			// nextMovesAllowed = moveRUMMYRUMMYCUT;
			nextMovesAllowed = moveRUMMYDECLARE | moveRUMMYNEWCARD
					| moveRUMMYFOLD;
			if (manualSplitOn)
				nextMovesAllowed |= moveRUMMYMANUALSPLIT;

			// give cards to all players
			for (int m = 0; m < MAXPLAYERS; m++) {
				if (players[m] != null && players[m].getPresence() != null
						&& isActive(players[m])
						) {
					_cat.debug("coming here : " + players[m].getName());
					if (isLost(players[m])){
						_cat.debug("player lost");
						continue;
					}
					if (isToJoinNextGame(players[m])){
						_cat.debug("player to join next game");
						continue;
					}
					//dealer, rummy joker card, points per rupee, table id
					players[m].setRUMMYMovesStr("&TID^" + tid);
					players[m].setRUMMYMovesStr("&Dealer^" + dealer);
					players[m].setRUMMYMovesStr("&RummyCard^" + rummyCardJoker.toString());
					players[m].setRUMMYMovesStr("&PtsPerRupee^" + POINTSPERRUPEE);
					
					players[m].allotedCards = new Card[13];// always clear
																// the hand
																// here

					//player got cards. store it in db
					players[m].setRUMMYMovesStr("&Cards^");
					for (int i = 0; i < 13; i++) {
						int randCard = drawCard();
						Card cr = new Card(randCard);
						cr.setIsOpened(true);
						players[m].allotedCards[i] = cr;
						
						players[m].setRUMMYMovesStr(cr.toString());
						if (i < 12)
							players[m].setRUMMYMovesStr("`");
					}
					_cat.debug("cards given");
				}
			}
			
			//check if rummy player is still active.
			if (dealer == -1 || players[dealer] == null ||  isLost(players[dealer]) ||
					isLeft(players[dealer]) || isRemoved(players[dealer])){
				_cat.debug("bdk dealer gone!!!!");
				for (int m = 0; m < MAXPLAYERS; m++){
					//first player will be dealer
					if (players[m] != null && !isRemoved(players[m]) && !isLost(players[m]) && !isLeft(players[m])){
						dealer = m;
						break;
					}
				}
			}
			if (rummyPlayer == -1 || players[rummyPlayer] == null || isLost(players[rummyPlayer]) ||
					isLeft(players[rummyPlayer]) || isRemoved(players[rummyPlayer])){
				_cat.debug("bdk rummy player is gone. change it");
				rummyPlayer = getRightOfPlayer(dealer);
			}
			
			cardDealingOrder = rummyPlayer + "'" + dealer;
			
			chatOn = 0;
			chatMessage = "Cards given to players.";
			
			nextMovePlayer = -1;
			nextMoveExpTime = System.currentTimeMillis();
			gameStartTime = Calendar.getInstance();
			broadcastMessage(-1);
			// sleep for 10 seconds to allow clients to distribute cards
			try {
				Thread.currentThread().sleep(15000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			nextMovePlayer = rummyPlayer;
			
			nextMoveExpTime = System.currentTimeMillis();

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
		
			public void broadcastMessage(int notUsedPos) {
				_cat.debug("broadcasting response!!! + " + tid);
				StringBuffer temp = new StringBuffer();
				temp.append("Rummy2Pool201Server=Rummy2Pool201Server");
				temp.append(",gid=").append(_gid);
				temp.append(",grid=").append(rummygrid);
				temp.append(",rummygrid=").append(rummygrid);
				temp.append(",TID=").append(tid);
	
				temp.append(",Dealer=").append(dealer);
				temp.append(",RummyPlayer=").append(rummyPlayer);
				temp.append(",GameOn=").append(gameOngoing);
				
				if (!gameOngoing){
					double to = 60000 - (System.currentTimeMillis() - nextMoveExpTime);
					if (to < 0)
						to = 0;
					int toint = (int)(to/1000);
					temp.append(",TimerNG=").append(toint);
				}
				
				if (nextMovePlayer != -1 && nextMovePlayer < MAXPLAYERS && players[nextMovePlayer] != null && winnerString.isEmpty()){
					//for time bank
					if (players[nextMovePlayer].getUsingTimeBank()){
						temp.append(",DiscProtOn=").append((players[nextMovePlayer].getTimeBank()) * 10);
					}
				}
				
				temp.append(",RoundNo=").append(roundNum);
				
				temp.append(",chatMsgOn=").append(chatOn);
				if (!chatMessage.isEmpty()){
					String strcrypt = encrypt(chatMessage);
					temp.append(",chatMsg=").append(strcrypt);
//					_cat.debug(decrypt(strcrypt));
					chatMessage = "";
				}
				else
					temp.append(",chatMsg=");
	
				if (rummyCardJoker != null)
					temp.append(",RummyJoker=").append(rummyCardJoker.toString());
				else
					temp.append(",RummyJoker=");
				
				if (discardCard != null && idPlayerValidDeclared == -1)
					temp.append(",DiscardCard=").append(discardCard.toString());
				else
					temp.append(",DiscardCard=");
				
				if (idPlayerValidDeclared != -1 && players[idPlayerValidDeclared] != null) {
					temp.append(",ValidDecPlyr=").append(
							players[idPlayerValidDeclared].name);
					temp.append(",ValidDecPlyrId=").append(
							idPlayerValidDeclared);
				}
				
				temp.append(",ManualSplit=" + manualSplitOn);
				temp.append(",ManualSplittor=" + holdNextMovePlayer);
				
				System.out.println("temp 111 : " + temp);
				
				String totString = "";
				for (int m = 0; m < MAXPLAYERS; m++){
					if (players[m] != null){
						totString += m + "'" + players[m].totalScore + "'" + players[m].getRUMMYStatus() + "|";
					}
				}
				temp.append(",TotalScores=").append(totString);
	
				temp.append(",NextMovePos=").append(nextMovePlayer);
				temp.append(",NextMoveId=").append(nextMovesAllowed);
				temp.append(",LastMove=").append(lastMove);
				temp.append(",LastMovePos=").append(lastMovePos);
				temp.append(",LastMoveType=").append(lastMoveString);
	
				temp.append(",DealingOrder=").append(cardDealingOrder);
	
				// add the bit about checking cards of players
				if (fixingDealerOngoing) {
					String str = "";
					for (int i = 0; i < MAXPLAYERS; i++) {
						if (players[i] != null && isActive(players[i])) {
							Card tempCr = new Card(players[i].fixPosCard);
							str += i + "`" + tempCr.toString() + "'";
						}
					}
					if (!str.isEmpty())
						str = str.substring(0, str.length() - 1);
					temp.append(",FixDealerProcess=").append(1);
					temp.append(",FixDealerCards=").append(str);
				}
				
				System.out.println("temp 2222 : " + temp);
				
				if (roundWinner != -1 && players[roundWinner] != null) {
					temp.append(",Winner=").append(roundWinner);
					String str = "";
					if (players[roundWinner].allotedCards != null) {
						for (int k = 0; k < players[roundWinner].allotedCards.length; k++)
							str += players[roundWinner].allotedCards[k].toString()
									+ "'";
						str = str.substring(0, str.length() - 1);
					}
	
					temp.append(",WinnerCards=").append(str);
					if (!players[roundWinner].cardsStrFromClient.isEmpty())
						temp.append(",WinnerCardsString=").append(
								players[roundWinner].cardsStrFromClient);
				}
				
				System.out.println("temp 333 : " + temp);
				
				String lostStr = "";
				if (winnerString != null && !winnerString.isEmpty() && amtWonString.isEmpty()) {
					temp.append(",GameResult=").append(winnerString);
					
					//for amount won string
					for (int i = 0; i < MAXPLAYERS; i++) {
						if (players[i] != null){
							amtWonString += i + "'" + (players[i].getRUMMYWinAmt() - players[i].getPresence().currentRoundBet()) + "|";
						}
					}
					if (!amtWonString.isEmpty())
						amtWonString = amtWonString.substring(0, amtWonString.length() - 1);
					
					temp.append(",AmountsWon=").append(amtWonString);
					
					if (winnerCount > 0 && winnerPos != null){
						for (int i = 0; i < MAXPLAYERS; i++) {
							if (players[i] != null) {
								for (int j = 0; j < winnerCount; j++){
									if (i != winnerPos[j]){
										lostStr += i + "'";
									}
								}
							}
						}
						if (!lostStr.isEmpty())
							lostStr = lostStr.substring(0, lostStr.length() - 1);
					}
				}
				else {
					for (int i = 0; i < MAXPLAYERS; i++) {
						if (players[i] != null) {
							if (isLost(players[i]))
								lostStr += i + "'";
						}
					}
					if (!lostStr.isEmpty())
						lostStr = lostStr.substring(0, lostStr.length() - 1);
				}
				
				temp.append(",PlayerLost=").append(lostStr);
				
				temp.append(",SplitDone=").append(splitOptedFor);
				
				temp.append(",RoundResult=").append(combinedRoundRes);
				
				temp.append(",Pot=").append(pot);
				
				if (roundWinner != -1 && players[roundWinner] != null)
					temp.append(",RoundWinner=").append(players[roundWinner].name);
				
				temp.append(",CurrentRoundResult=").append(currRoundResStr);
				// now create that amalgam of all players status, pos, chips
				StringBuffer tempPlayerDet = new StringBuffer();
				int nCount = 0;
				for (int i = 0; i < MAXPLAYERS; i++) {
					if (players[i] != null) {
						nCount++;
						tempPlayerDet.append("'" + i + ":" + players[i].getName()
								+ ":");
						tempPlayerDet.append(players[i].getRUMMYStatus() + ":"
								+ players[i].getPresence().getAmtAtTable());
					}
				}
				temp.append(",PlayerDetails=" + nCount + tempPlayerDet);
				
				System.out.println("temp 444 : " + temp);
	
				// this temp can be now sent to all observers on the table
				for (int i = 0; i < observers.size(); i++) {
					RummyProfile pro = (RummyProfile) observers.get(i);
					// if (!pro.isRummyPlayer()) {
					_cat.debug("sending to pro : " + pro.getName());
					sendMessage(temp, pro);
					// }
				}
				// for each presence, call sendMessage with their individual data
				for (int i = 0; i < MAXPLAYERS; i++) {
					if (players[i] != null) {
						if (players[i] != null) {
							StringBuffer tempPlayerData = new StringBuffer(temp);
							tempPlayerData.append(",PlayerPos=").append(i);
							if (players[i].allotedCards != null
									&& !fixingDealerOngoing && poolStarted) {
								String str123 = "";
								for (int k = 0; k < players[i].allotedCards.length; k++)
									str123 += players[i].allotedCards[k].toString()
											+ "'";
								
								if (!str123.isEmpty())
									str123 = str123.substring(0, str123.length() - 1);
								
								tempPlayerData.append(",Cards=" + str123);
							} else
								tempPlayerData.append(",Cards=");
		
							tempPlayerData.append(",NewCardAdded=" + newCardAdded);
							sendMessage(tempPlayerData, players[i]);
							_cat.debug("message : " + tempPlayerData + " to player : " + i);
						}
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
			temp.append("Rummy2Pool201Server=Rummy2Pool201Server");
			temp.append(",gid=").append(_gid);
			temp.append(",grid=").append(rummygrid);
			temp.append(",rummygrid=").append(rummygrid);
			temp.append(",TID=").append(tid);
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
			for (int i = 0; i < MAXPLAYERS; i++) {
				if (players[i] != null && !isLeft(players[i]) && !isRemoved(players[i])) {
					nCount++;
					tempPlayerDet.append("'" + i + ":" + players[i].getName()
							+ ":");
					tempPlayerDet.append(players[i].getRUMMYStatus() + ":"
							+ players[i].getPresence().getAmtAtTable());
				}
			}
			temp.append(",PlayerDetails=" + nCount + tempPlayerDet);
			
			_cat.debug("temp from kick out : " + temp);
			
			// this temp can be now sent to all observers on the table
			for (int i = 0; i < observers.size(); i++) {
				RummyProfile pro = (RummyProfile) observers.get(i);
				if (!pro.isRummyPlayer()) {
					sendMessage(temp, pro);
				}
			}
			// for each presence, call sendMessage with their individual data
//			 for (int i = 0; i < MAXPLAYERS; i++) {
				if (prpos > -1 && players[prpos] != null) {
					StringBuffer tempPlayerData = new StringBuffer(temp);
					tempPlayerData.append(",PlayerPos=").append(prpos);
					sendMessage(tempPlayerData, players[prpos]);
				}
//			 }
		}

		public void sendErrorMessage(int prpos, int resCode) {
			StringBuffer temp = new StringBuffer();
			temp.append("Rummy2Pool201Server=Rummy2Pool201Server");
			temp.append(",gid=").append(_gid);
			temp.append(",grid=").append(rummygrid);
			temp.append(",rummygrid=").append(rummygrid);
			temp.append(",TID=").append(tid);

			// for reason
			if (resCode == 0) {
				temp.append(",MsgDropped=").append("WrongMove");
			} else if (resCode == 1) {
				temp.append(",MsgDropped=").append("WrongCard");
			} else if (resCode == 3) {
				temp.append(",MsgDropped=").append("NotAllowed");
			} else if (resCode == 4) {
				temp.append(",MsgDropped=").append("CantTakeDiscardedJoker");
			} else if (resCode == 5) {
				temp.append(",MsgDropped=").append("Score-175");
			} else if (resCode == 6) {
				temp.append(",MsgDropped=").append("NoChips");
			}
			
			else if (resCode == 11) {
				temp.append(",PenalCards=" + globalPenal + "'firstLife="
						+ globalFirstLife + "'secondLife=" + globalSecondLife);
			} else {
				temp.append(",MsgDropped=GetLost");
			}

			temp.append(",Dealer=").append(dealer);
			temp.append(",RummyPlayer=").append(rummyPlayer);
			temp.append(",GameOn=").append(gameOngoing);
			
			temp.append(",RoundNo=").append(roundNum);
			
			temp.append(",Pot=").append(pot);

			if (rummyCardJoker != null)
				temp.append(",RummyJoker=").append(rummyCardJoker.toString());
			else
				temp.append(",RummyJoker=");
			if (discardCard != null)
				temp.append(",DiscardCard=").append(discardCard.toString());
			else
				temp.append(",DiscardCard=");

			temp.append(",NextMovePos=").append(nextMovePlayer);
			temp.append(",NextMoveId=").append(nextMovesAllowed);
			temp.append(",LastMove=").append(lastMove);
			temp.append(",LastMovePos=").append(lastMovePos);
			temp.append(",LastMoveType=").append(lastMoveString);

			temp.append(",DealingOrder=").append(cardDealingOrder);

			// add the bit about checking cards of players
			// now create that amalgam of all players status, pos, chips
			StringBuffer tempPlayerDet = new StringBuffer();
			int nCount = 0;
			for (int i = 0; i < MAXPLAYERS; i++) {
				if (players[i] != null) {
					nCount++;
					tempPlayerDet.append("'" + i + ":" + players[i].getName()
							+ ":");
					tempPlayerDet.append(players[i].getRUMMYStatus() + ":"
							+ players[i].getPresence().getAmtAtTable());
				}
			}
			temp.append(",PlayerDetails=" + nCount + tempPlayerDet);

			// for each presence, call sendMessage with their individual data
			int i = prpos;
			if (i > -1 && players[i] != null) {
				StringBuffer tempPlayerData = new StringBuffer(temp);
				tempPlayerData.append(",PlayerPos=").append(i);
				if (players[i].allotedCards != null && !fixingDealerOngoing) {
					String str = "";
					for (int k = 0; k < players[i].allotedCards.length; k++)
						str += players[i].allotedCards[k].toString() + "'";
					str = str.substring(0, str.length() - 1);
					tempPlayerData.append(",Cards=" + str);
				} else
					tempPlayerData.append(",Cards=");

				tempPlayerData.append(",NewCardAdded=" + newCardAdded);
				sendMessage(tempPlayerData, players[i]);
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
		for (int i = 0; i < tables.length; i++) {
			if (!tables[i].validTable)
				return i;
		}
		return -1;
	}

	// rummyReload
	public StringBuffer rummyReload(Player.Presence p, String movedet) {
		StringBuffer buf = new StringBuffer();

		int tid = getTID(movedet);// table id
		if (tid >= MAXTABLES || tid < 0 || !tables[tid].validTable) {
			buf.append("Rummy2Pool201Server=Rummy2Pool201Server,grid=")
					.append(-1)
					.append(",MsgDropped=InvalidTable,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}

		int pos = getPos(movedet);
		if (pos < 0 || pos > MAXPLAYERS) {
			buf.append("Rummy2Pool201Server=Rummy2Pool201Server,grid=")
					.append(-1)
					.append(",MsgDropped=InvalidPos,player-details=" + p.name()
							+ "|" + p.netWorth());
			return buf;
		}

		tables[tid].players[pos].setReloadReq(true);
		buf.append("Rummy2Pool201Server=Rummy2Pool201Server,grid=").append(p.getGRID())
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
		_cat.debug("Rummy2Pool201Server game - sit in req --" + movedet);

		if (movedet == null || movedet.equals("null")) {
			return null;
		}
		if (tid >= MAXTABLES || tid < 0 || !tables[tid].validTable) {
			buf.append("Rummy2Pool201Server=Rummy2Pool201Server,grid=")
					.append(-1)
					.append(",TID=").append(tid)
					.append(",MsgDropped=InvalidTable,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}

		int origPos = -1;
		origPos = tables[tid].findPos(p);
		if (moveId == 0) {
			// just wants to observe, it is ok
			// put a conditon over here for the plaeyrs who want to come back
			// they had placed some bets and are still valid players on the
			// table

			if (!tables[tid].gameOngoing
					&& tables[tid].getCurrentPlayersCount() == 0)
				tables[tid].nextMoveExpTime = System.currentTimeMillis();
			
//			if (tables[tid].getCurrentPlayersCount() >= tables[tid]
//					.getMaxPlayers() && origPos == -1) {
//				_cat.debug("max players coutn reached");
//				buf.append("Rummy2Pool201Server=Rummy2Pool201Server,grid=")
//						.append(-1)
//						.append(",TID=")
//						.append(tid)
//						.append(",MsgDropped=TableFull,player-details="
//								+ p.name() + "|" + p.netWorth());
//				return buf;
//			}

			if (origPos != -1) {
				// found him, seated already.
				_cat.debug("seated already from rummysitin : "
						+ origPos);
			} else {

				if (p.getAmtAtTable() < tables[tid].buyIn * tables[tid].POINTSPERRUPEE) {
					buf.append("Rummy2Pool201Server=Rummy2Pool201Server,grid=")
							.append(-1)
							.append(",TID=").append(tid)
							.append(",MsgDropped=PlayerBroke,player-details="
									+ p.name() + "|" + p.netWorth());
					return buf;
				}
				// now check if there is space on the table
		/*		if (tables[tid].getCurrentPlayersCount() >= tables[tid]
						.getMaxPlayers()) {
					buf.append("Rummy2Pool201Server=Rummy2Pool201Server,grid=")
							.append(p.getGRID())
							.append(",TID=").append(tid)
							.append(",MsgDropped=TableFull,player-details="
									+ p.name() + "|" + p.netWorth());
					return buf;
				} */
				// create a new rummyprofile for this presence
				RummyProfile kp = new RummyProfile();
				kp.setName(p.name());
				kp.setGameStartWorth(p.getAmtAtTable());
				kp.setPresence(p);
				p.setKPIndex(tables[tid].addObserver(kp));
				kp.setRUMMYStatus(0);
				kp.rummyLastMoveTime = System.currentTimeMillis();
				
				//for private tables, observor and seating is same
				//for other, they are 2 different events
					buf.append("Rummy2Pool201Server=Rummy2Pool201Server,grid=")
							.append(-1)
							.append(",TID=").append(tid);
							
					if (tables[tid].getCountTotalPlayers() <= 0)
							buf.append(",player-details=" + p.name() + "|"
									+ p.netWorth());
					else {
						String str = "";
						for (int k = 0; k < tables[tid].players.length; k++){
							
							if (tables[tid].players[k] != null)
								str += tables[tid].players[k].getName() + "|" + tables[tid].players[k].getPresence().netWorth() + "`";
							
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
				&& p.getAmtAtTable() < tables[tid].buyIn * tables[tid].POINTSPERRUPEE) {
			buf.append("Rummy2Pool201Server=Rummy2Pool201Server,grid=")
					.append(-1)
					.append(",TID=").append(tid)
					.append(",MsgDropped=PlayerBroke,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}
		// now check if there is space on the table
		if (origPos == -1
				&& tables[tid].getCurrentPlayersCount() >= tables[tid]
						.getMaxPlayers()) {
			buf.append("Rummy2Pool201Server=Rummy2Pool201Server,grid=")
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
			obsPos = tables[tid].findObsPos(p);
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
		_cat.debug("Rummy2Pool201Server game - move req --" + movedet);

		if (movedet == null || movedet.equals("null")) {
			buf.append("Rummy2Pool201Server=Rummy2Pool201Server,grid=")
					.append(-1)
					.append(",MsgDropped=MsgDropped,player-details=" + p.name()
							+ "|" + p.netWorth());
			return buf;
		}
		if (tid >= MAXTABLES) {
			buf.append("Rummy2Pool201Server=Rummy2Pool201Server,grid=")
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
		RummyProfile kp = tables[tid].findProfile(p);
		if (kp == null) {
			int obs = tables[tid].findObsPos(p);
			if (obs > -1) {
				kp = tables[tid].findObservor(obs);
				if (kp != null) {
					tables[tid].removeObserver(kp);
				}
			}
			return;
		}

		if (tables[tid].isLeft(kp)) {
			return;
		}

		// just mark the player as left. on completion of cycle, the player will
		// be removed
		kp.rummyLeftTableTime = System.currentTimeMillis();
		kp.setRUMMYMovesStr("&Leave");
		kp.setRUMMYMoveId(moveRUMMYLEFT);
		int pos = kp.pos();
		
		kp.setRUMMYStatus(statusLEFT);
		
		tables[tid].handleMoveNoAction(kp.pos());
		
		if (tables[tid].players[pos] != null){
			_cat.debug("take action plyr left : " + kp.pos()
					+ " from table : " + tid);
			kp.setRUMMYStatus(statusLEFT);
		}
	}
}
