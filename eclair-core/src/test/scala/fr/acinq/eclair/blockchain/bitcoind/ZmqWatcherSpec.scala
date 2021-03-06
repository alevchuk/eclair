/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.bitcoind

import java.util.concurrent.atomic.AtomicLong

import akka.Done
import akka.actor.{ActorRef, Props}
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.{OutPoint, Script}
import fr.acinq.eclair.blockchain.WatcherSpec._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.bitcoind.zmq.ZMQActor
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_DEPTHOK, BITCOIN_FUNDING_SPENT}
import fr.acinq.eclair.{TestKitBaseClass, randomBytes32}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JValue
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.Promise
import scala.concurrent.duration._

class ZmqWatcherSpec extends TestKitBaseClass with AnyFunSuiteLike with BitcoindService with BeforeAndAfterAll with Logging {

  var zmqBlock: ActorRef = _
  var zmqTx: ActorRef = _

  override def beforeAll(): Unit = {
    logger.info("starting bitcoind")
    startBitcoind()
    waitForBitcoindReady()
    logger.info("starting zmq actors")
    val (zmqBlockConnected, zmqTxConnected) = (Promise[Done](), Promise[Done]())
    zmqBlock = system.actorOf(Props(new ZMQActor(s"tcp://127.0.0.1:$bitcoindZmqBlockPort", Some(zmqBlockConnected))))
    zmqTx = system.actorOf(Props(new ZMQActor(s"tcp://127.0.0.1:$bitcoindZmqTxPort", Some(zmqTxConnected))))
    awaitCond(zmqBlockConnected.isCompleted && zmqTxConnected.isCompleted)
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    logger.info("stopping zmq actors")
    system.stop(zmqBlock)
    system.stop(zmqTx)
    logger.info("stopping bitcoind")
    stopBitcoind()
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }

  test("add/remove watches from/to utxo map") {
    val m0 = Map.empty[OutPoint, Set[Watch]]
    val txid = randomBytes32
    val outputIndex = 42
    val utxo = OutPoint(txid.reverse, outputIndex)

    val w1 = WatchSpent(null, txid, outputIndex, randomBytes32, BITCOIN_FUNDING_SPENT)
    val w2 = WatchSpent(null, txid, outputIndex, randomBytes32, BITCOIN_FUNDING_SPENT)
    val w3 = WatchSpentBasic(null, txid, outputIndex, randomBytes32, BITCOIN_FUNDING_SPENT)
    val w4 = WatchSpentBasic(null, randomBytes32, 5, randomBytes32, BITCOIN_FUNDING_SPENT)
    val w5 = WatchConfirmed(null, txid, randomBytes32, 3, BITCOIN_FUNDING_SPENT)

    // we test as if the collection was immutable
    val m1 = addWatchedUtxos(m0, w1)
    assert(m1.keySet == Set(utxo) && m1.size == 1)
    val m2 = addWatchedUtxos(m1, w2)
    assert(m2.keySet == Set(utxo) && m2(utxo).size == 2)
    val m3 = addWatchedUtxos(m2, w3)
    assert(m3.keySet == Set(utxo) && m3(utxo).size == 3)
    val m4 = addWatchedUtxos(m3, w4)
    assert(m4.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m3(utxo).size == 3)
    val m5 = addWatchedUtxos(m4, w5)
    assert(m5.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m5(utxo).size == 3)
    val m6 = removeWatchedUtxos(m5, w3)
    assert(m6.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m6(utxo).size == 2)
    val m7 = removeWatchedUtxos(m6, w3)
    assert(m7.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m7(utxo).size == 2)
    val m8 = removeWatchedUtxos(m7, w2)
    assert(m8.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m8(utxo).size == 1)
    val m9 = removeWatchedUtxos(m8, w1)
    assert(m9.keySet == Set(OutPoint(w4.txId.reverse, w4.outputIndex)))
    val m10 = removeWatchedUtxos(m9, w4)
    assert(m10.isEmpty)
  }

  test("watch for confirmed transactions") {
    val probe = TestProbe()
    val blockCount = new AtomicLong()
    val watcher = system.actorOf(ZmqWatcher.props(blockCount, new ExtendedBitcoinClient(bitcoinrpcclient)))
    val (address, _) = getNewAddress(bitcoincli)
    val tx = sendToAddress(bitcoincli, address, 1.0)

    val listener = TestProbe()
    probe.send(watcher, WatchConfirmed(listener.ref, tx.txid, tx.txOut.head.publicKeyScript, 4, BITCOIN_FUNDING_DEPTHOK))
    probe.send(watcher, WatchConfirmed(listener.ref, tx.txid, tx.txOut.head.publicKeyScript, 4, BITCOIN_FUNDING_DEPTHOK)) // setting the watch multiple times should be a no-op
    generateBlocks(bitcoincli, 5)
    assert(listener.expectMsgType[WatchEventConfirmed].tx.txid === tx.txid)
    listener.expectNoMsg(1 second)

    // If we try to watch a transaction that has already been confirmed, we should immediately receive a WatchEventConfirmed.
    probe.send(watcher, WatchConfirmed(listener.ref, tx.txid, tx.txOut.head.publicKeyScript, 4, BITCOIN_FUNDING_DEPTHOK))
    assert(listener.expectMsgType[WatchEventConfirmed].tx.txid === tx.txid)
    listener.expectNoMsg(1 second)
    system.stop(watcher)
  }

  test("watch for spent transactions") {
    val probe = TestProbe()
    val blockCount = new AtomicLong()
    val watcher = system.actorOf(ZmqWatcher.props(blockCount, new ExtendedBitcoinClient(bitcoinrpcclient)))
    val (address, priv) = getNewAddress(bitcoincli)
    val tx = sendToAddress(bitcoincli, address, 1.0)
    val outputIndex = tx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey)))
    val (tx1, tx2) = createUnspentTxChain(tx, priv)

    val listener = TestProbe()
    probe.send(watcher, WatchSpent(listener.ref, tx, outputIndex, BITCOIN_FUNDING_SPENT))
    listener.expectNoMsg(1 second)
    probe.send(bitcoincli, BitcoinReq("sendrawtransaction", tx1.toString()))
    probe.expectMsgType[JValue]
    // tx and tx1 aren't confirmed yet, but we trigger the WatchEventSpent when we see tx1 in the mempool.
    listener.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx1))
    // Let's confirm tx and tx1: seeing tx1 in a block should trigger WatchEventSpent again.
    generateBlocks(bitcoincli, 2)
    listener.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx1))

    // Let's submit tx2, and set a watch after it has been confirmed this time.
    probe.send(bitcoincli, BitcoinReq("sendrawtransaction", tx2.toString()))
    probe.expectMsgType[JValue]
    listener.expectNoMsg(1 second)
    generateBlocks(bitcoincli, 1)
    probe.send(watcher, WatchSpent(listener.ref, tx1, 0, BITCOIN_FUNDING_SPENT))
    listener.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx2))
    system.stop(watcher)
  }

}