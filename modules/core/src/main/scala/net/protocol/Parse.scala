// Copyright (c) 2018 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package skunk.net.protocol

import cats.effect.Resource
import cats.implicits._
import cats.MonadError
import skunk.exception.PostgresErrorException
import skunk.net.message.{ Parse => ParseMessage, Close => _, _ }
import skunk.net.MessageSocket
import skunk.net.Protocol.StatementId
import skunk.Statement
import skunk.util.Namer
import skunk.util.Typer
import skunk.exception.UnknownTypeException
import natchez.Trace

trait Parse[F[_]] {
  def apply[A](statement: Statement[A], ty: Typer): Resource[F, StatementId]
}

object Parse {

  def apply[F[_]: MonadError[?[_], Throwable]: Exchange: MessageSocket: Namer: Trace]: Parse[F] =
    new Parse[F] {

      override def apply[A](statement: Statement[A], ty: Typer): Resource[F, StatementId] =
        statement.encoder.oids(ty) match {

          case Right(os) =>
            Resource.make {
              exchange("parse") {
                for {
                  id <- nextName("statement").map(StatementId)
                  _  <- Trace[F].put(
                          "statement-name"            -> id.value,
                          "statement-sql"             -> statement.sql,
                          "statement-parameter-types" -> os.map(n => ty.typeForOid(n, -1).getOrElse(n)).mkString("[", ", ", "]")
                        )
                  _  <- send(ParseMessage(id.value, statement.sql, os))
                  _  <- send(Flush)
                  _  <- flatExpect {
                          case ParseComplete       => ().pure[F]
                          case ErrorResponse(info) => syncAndFail(statement, info)
                        }
                } yield id
              }
            } { Close[F].apply }

          case Left(err) =>
            Resource.liftF(UnknownTypeException(statement, err).raiseError[F, StatementId])

        }

      def syncAndFail(statement: Statement[_], info: Map[Char, String]): F[Unit] =
        for {
          hi <- history(Int.MaxValue)
          _  <- send(Sync)
          _  <- expect { case ReadyForQuery(_) => }
          a  <- new PostgresErrorException(
                  sql       = statement.sql,
                  sqlOrigin = Some(statement.origin),
                  info      = info,
                  history   = hi,
                ).raiseError[F, Unit]
        } yield a

    }

}